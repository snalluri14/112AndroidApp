"""In-memory state (the 'database'), snapshotted to a JSON file after every change.

The fleet is not fixed: an ambulance appears when a phone registers its vehicle ID, and
disappears when its last phone un-registers (unless it is on a dispatch).
"""
import asyncio
import json
import logging
import os
import secrets
from datetime import datetime, timezone
from pathlib import Path

from .models import (
    Assignment, AssignmentIn, Device, HospitalLeg, LatLon, Leg, Status, Vehicle, VehicleSummary,
)

log = logging.getLogger(__name__)


def _now() -> datetime:
    return datetime.now(timezone.utc)


class NotAllowed(Exception):
    """Vehicle ID is not in the optional FLEET_IDS allow-list."""


class Store:
    def __init__(self, allowed_ids: list[int] | None, state_file: Path | None) -> None:
        self._state_file = state_file
        self._lock = asyncio.Lock()
        # None = any vehicle ID may register
        self.allowed: set[int] | None = set(allowed_ids) if allowed_ids else None
        self.vehicles: dict[int, Vehicle] = {}
        # registered phones per ambulance: token -> Device
        self.devices: dict[int, dict[str, Device]] = {}
        # vehicles whose Back-to-Base call is in flight (prevents double calls from repeated taps)
        self.releasing: set[int] = set()
        self._load()

    # ---------- persistence ----------

    def _load(self) -> None:
        if not self._state_file or not self._state_file.exists():
            return
        try:
            data = json.loads(self._state_file.read_text(encoding="utf-8"))
            for raw in data.get("vehicles", []):
                v = Vehicle.model_validate(raw)
                self.vehicles[v.id] = v
            for vid, devs in data.get("devices", {}).items():
                self.devices[int(vid)] = {tok: Device.model_validate(d) for tok, d in devs.items()}
        except (OSError, ValueError) as e:
            log.error("Could not read %s, starting fresh: %s", self._state_file, e)
            self.vehicles, self.devices = {}, {}
            return
        busy = [v.id for v in self.vehicles.values() if v.status == Status.BUSY]
        log.info("Restored %d vehicles from %s (busy: %s)", len(self.vehicles), self._state_file, busy or "none")

    def _save(self) -> None:
        if not self._state_file:
            return
        data = {
            "vehicles": [v.model_dump(mode="json") for v in self.vehicles.values()],
            "devices": {
                str(vid): {tok: d.model_dump(mode="json") for tok, d in devs.items()}
                for vid, devs in self.devices.items() if devs
            },
        }
        tmp = self._state_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(data), encoding="utf-8")
        os.replace(tmp, self._state_file)  # atomic: never leaves a half-written file

    # ---------- reads ----------

    def exists(self, vid: int) -> bool:
        return vid in self.vehicles

    def summaries(self) -> list[VehicleSummary]:
        return [self.summary(vid) for vid in sorted(self.vehicles)]

    def summary(self, vid: int) -> VehicleSummary:
        v = self.vehicles[vid]
        return VehicleSummary(id=v.id, status=v.status, busy_since=v.busy_since)

    def assignment(self, vid: int) -> Assignment | None:
        return self.vehicles[vid].assignment

    def token_valid(self, vid: int, token: str | None) -> bool:
        return bool(token) and token in self.devices.get(vid, {})

    def device_list(self, vid: int) -> list[Device]:
        return list(self.devices.get(vid, {}).values())

    # ---------- registration ----------

    async def register(self, vid: int) -> tuple[str, bool]:
        """Registers a phone for this ambulance. Returns (token, vehicle_was_new)."""
        if self.allowed is not None and vid not in self.allowed:
            raise NotAllowed(vid)
        async with self._lock:
            is_new = vid not in self.vehicles
            if is_new:
                self.vehicles[vid] = Vehicle(id=vid)
            token = secrets.token_urlsafe(24)
            now = _now()
            self.devices.setdefault(vid, {})[token] = Device(registered_at=now, last_seen=now)
            self._save()
            return token, is_new

    async def unregister(self, vid: int, token: str) -> bool:
        """Removes this phone. Returns True if the vehicle itself was removed from the fleet."""
        async with self._lock:
            self.devices.get(vid, {}).pop(token, None)
            removed = self._drop_if_unused(vid)
            self._save()
            return removed

    def _drop_if_unused(self, vid: int) -> bool:
        """A vehicle with no phones left disappears from the list, unless it is on a dispatch."""
        v = self.vehicles.get(vid)
        if v and not self.devices.get(vid) and v.status == Status.AVAILABLE:
            del self.vehicles[vid]
            self.devices.pop(vid, None)
            return True
        return False

    async def touch(self, vid: int, token: str) -> None:
        async with self._lock:
            d = self.devices.get(vid, {}).get(token)
            if d:
                d.last_seen = _now()
                self._save()

    # ---------- dispatch ----------

    async def try_assign(self, a: AssignmentIn) -> tuple[Assignment | None, str | None]:
        """Marks the vehicle BUSY. Returns (assignment, None) or (None, reason)."""
        async with self._lock:
            v = self.vehicles.get(a.ambulance_id)
            if v is None:
                return None, "ambulance not registered in the app"
            if v.status == Status.BUSY:
                return None, "ambulance is busy"
            now = _now()
            patient = a.polyline[-1]  # patient location = end of the ambulance route
            assignment = Assignment(
                ambulance_id=a.ambulance_id,
                start=LatLon(lat=a.ambulance_lat, lon=a.ambulance_lon),
                to_patient=Leg(destination=LatLon(lat=patient[0], lon=patient[1]), polyline=a.polyline),
                to_hospital=HospitalLeg(
                    destination=LatLon(lat=a.hospital.lat, lon=a.hospital.lon),
                    polyline=a.hospital.polyline,
                    name=a.hospital.name,
                ),
                assigned_at=now,
            )
            v.status, v.assignment, v.busy_since = Status.BUSY, assignment, now
            self._save()
            return assignment, None

    async def begin_release(self, vid: int) -> str | None:
        """Claims the vehicle for a Back-to-Base call. Returns a reason if it can't."""
        async with self._lock:
            if self.vehicles[vid].status != Status.BUSY:
                return "already available"
            if vid in self.releasing:
                return "back-to-base already in progress"
            self.releasing.add(vid)
            return None

    async def finish_release(self, vid: int, success: bool) -> None:
        """Marks AVAILABLE only if the dispatcher confirmed; otherwise it stays BUSY."""
        async with self._lock:
            self.releasing.discard(vid)
            if success:
                v = self.vehicles[vid]
                v.status, v.assignment, v.busy_since = Status.AVAILABLE, None, None
                self._save()
