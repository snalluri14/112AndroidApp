"""Request / response shapes."""
from datetime import datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_validator

# A point is [lat, lon], exactly as the dispatcher sends it.
Point = list[float]


def _check_polyline(points: list[Point]) -> list[Point]:
    if len(points) < 2:
        raise ValueError("polyline needs at least 2 points")
    for p in points:
        if len(p) != 2:
            raise ValueError("each polyline point must be [lat, lon]")
        lat, lon = p
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise ValueError(f"point out of range: {p}")
    return points


# ---------- Incoming from dispatcher (extra fields are ignored) ----------

class HospitalIn(BaseModel):
    model_config = ConfigDict(extra="ignore")

    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    polyline: list[Point]
    name: str | None = None  # optional, shown if they ever send it

    @field_validator("polyline")
    @classmethod
    def _polyline(cls, v: list[Point]) -> list[Point]:
        return _check_polyline(v)


class AssignmentIn(BaseModel):
    model_config = ConfigDict(extra="ignore")

    ambulance_id: int
    ambulance_lat: float = Field(ge=-90, le=90)
    ambulance_lon: float = Field(ge=-180, le=180)
    polyline: list[Point]
    hospital: HospitalIn

    @field_validator("polyline")
    @classmethod
    def _polyline(cls, v: list[Point]) -> list[Point]:
        return _check_polyline(v)


class DispatchIn(BaseModel):
    model_config = ConfigDict(extra="ignore")

    assignments: list[AssignmentIn] = Field(min_length=1)


# ---------- Stored / sent to the app ----------

class Status(str, Enum):
    AVAILABLE = "AVAILABLE"
    BUSY = "BUSY"


class LatLon(BaseModel):
    lat: float
    lon: float


class Leg(BaseModel):
    destination: LatLon
    polyline: list[Point]


class HospitalLeg(Leg):
    name: str | None = None


class Assignment(BaseModel):
    ambulance_id: int
    start: LatLon
    to_patient: Leg          # ambulance -> patient (patient = last polyline point)
    to_hospital: HospitalLeg  # patient -> hospital
    assigned_at: datetime


class Vehicle(BaseModel):
    id: int
    status: Status = Status.AVAILABLE
    assignment: Assignment | None = None
    busy_since: datetime | None = None


class Device(BaseModel):
    """One registered phone."""
    registered_at: datetime
    last_seen: datetime


class VehicleStats(BaseModel):
    id: int
    status: Status
    phones: int
    online: bool
    first_registered: datetime | None
    last_seen: datetime | None


class Stats(BaseModel):
    registered_vehicles: int
    registered_phones: int
    online_vehicles: int
    busy_vehicles: int
    vehicles: list[VehicleStats]


class VehicleSummary(BaseModel):
    """What every app sees in the fleet list - no route data."""
    id: int
    status: Status
    busy_since: datetime | None = None


class RegisterIn(BaseModel):
    ambulance_id: int = Field(gt=0)


class RegisterOut(BaseModel):
    ambulance_id: int
    token: str


class DispatchResult(BaseModel):
    accepted: list[int]
    rejected: list[dict]
