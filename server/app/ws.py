"""WebSocket push: status changes go to every phone, routes only to the assigned phone."""
import logging

from fastapi import WebSocket

log = logging.getLogger(__name__)


class ConnectionManager:
    def __init__(self) -> None:
        self._by_vehicle: dict[int, set[WebSocket]] = {}

    def add(self, vid: int, ws: WebSocket) -> None:
        self._by_vehicle.setdefault(vid, set()).add(ws)

    def remove(self, vid: int, ws: WebSocket) -> None:
        conns = self._by_vehicle.get(vid)
        if conns:
            conns.discard(ws)
            if not conns:
                del self._by_vehicle[vid]

    def is_online(self, vid: int) -> bool:
        return bool(self._by_vehicle.get(vid))

    def online_vehicle_count(self) -> int:
        return sum(1 for conns in self._by_vehicle.values() if conns)

    async def _send(self, vid: int, ws: WebSocket, message: dict) -> None:
        try:
            await ws.send_json(message)
        except Exception:  # dead socket - drop it, the phone resyncs on reconnect
            log.info("Dropping dead socket for vehicle %s", vid)
            self.remove(vid, ws)

    async def send_to_vehicle(self, vid: int, message: dict) -> None:
        for ws in list(self._by_vehicle.get(vid, ())):
            await self._send(vid, ws, message)

    async def broadcast(self, message: dict) -> None:
        for vid, conns in list(self._by_vehicle.items()):
            for ws in list(conns):
                await self._send(vid, ws, message)
