"""Ambulance dispatch server.

Dispatcher-facing (X-API-Key):
  POST /api/dispatch                         - assign ambulances (marks BUSY, pushes route to that phone only)
  GET  /api/stats                            - how many ambulances / phones are registered and online
Phone-facing:
  POST /api/devices/register                 - phone enters its vehicle ID once, gets a token
  POST /api/vehicles/{id}/unregister         - phone stops being this ambulance ("Change vehicle")
  GET  /api/vehicles                         - fleet list with statuses (no routes)
  GET  /api/vehicles/{id}/assignment         - own route only (token required)
  POST /api/vehicles/{id}/back-to-base       - driver taps Back to Base: calls the dispatcher's
                                               Back-to-Base endpoint, then marks AVAILABLE
  WS   /ws?ambulance_id=..&token=..          - live updates

The fleet is not fixed: an ambulance is listed once a phone registers its ID.
"""
import logging
import secrets
from contextlib import asynccontextmanager

from fastapi import Body, Depends, FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect, status
from fastapi.responses import FileResponse, JSONResponse

from .back_to_base import notify_back_to_base
from .config import settings
from .models import (
    Assignment, AssignmentIn, DispatchIn, DispatchResult, RegisterIn, RegisterOut, Stats, Status, VehicleStats,
    VehicleSummary,
)
from .store import NotAllowed, Store
from .ws import ConnectionManager

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("dispatch")


def create_app(fleet_ids: list[int] | None = None, state_file=..., dispatch_api_key: str | None = None) -> FastAPI:
    allowed = settings.fleet_ids if fleet_ids is None else fleet_ids
    sfile = settings.state_file if state_file is ... else state_file
    api_key = settings.dispatch_api_key if dispatch_api_key is None else dispatch_api_key

    store = Store(allowed, sfile)
    conns = ConnectionManager()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if not api_key:
            log.warning("DISPATCH_API_KEY not set - dispatcher endpoints are open to anyone (dev only)")
        log.info("Registration: %s", f"{len(allowed)} allowed IDs" if allowed else "open to any vehicle ID")
        yield

    app = FastAPI(title="Ambulance Dispatch", version="0.2.0", lifespan=lifespan)
    app.state.store, app.state.conns = store, conns

    # ---------- auth helpers ----------

    def require_dispatcher(x_api_key: str | None = Header(default=None)) -> None:
        if api_key and not (x_api_key and secrets.compare_digest(x_api_key, api_key)):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid or missing X-API-Key")

    def device_token(ambulance_id: int, authorization: str | None = Header(default=None)) -> str:
        token = authorization.removeprefix("Bearer ").strip() if authorization else ""
        if not store.exists(ambulance_id) or not store.token_valid(ambulance_id, token):
            raise HTTPException(status.HTTP_403_FORBIDDEN, "this device is not registered for this ambulance")
        return token

    async def push_status(vid: int) -> None:
        await conns.broadcast({"type": "status", "vehicle": store.summary(vid).model_dump(mode="json")})

    async def push_fleet() -> None:
        """Ambulance added to / removed from the list."""
        await conns.broadcast({"type": "fleet", "vehicles": [s.model_dump(mode="json") for s in store.summaries()]})

    # ---------- dispatcher ----------

    @app.post(
        "/api/dispatch",
        response_model=DispatchResult,
        dependencies=[Depends(require_dispatcher)],
        responses={409: {"model": DispatchResult, "description": "No assignment accepted"}},
    )
    async def dispatch(payload: DispatchIn | AssignmentIn = Body(...)):
        """Accepts {"assignments": [...]} (same shape as the 112 response) or a single assignment object.
        Each ambulance is handled independently: a BUSY or unregistered ambulance is rejected, others proceed."""
        items = payload.assignments if isinstance(payload, DispatchIn) else [payload]
        accepted, rejected = [], []
        for item in items:
            assignment, reason = await store.try_assign(item)
            if assignment is None:
                rejected.append({"ambulance_id": item.ambulance_id, "reason": reason})
                log.info("Dispatch rejected for %s: %s", item.ambulance_id, reason)
                continue
            accepted.append(item.ambulance_id)
            log.info("Ambulance %s dispatched (phone online: %s)", item.ambulance_id, conns.is_online(item.ambulance_id))
            await conns.send_to_vehicle(item.ambulance_id, {"type": "assignment", "assignment": assignment.model_dump(mode="json")})
            await push_status(item.ambulance_id)

        result = DispatchResult(accepted=accepted, rejected=rejected)
        if not accepted:
            return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=result.model_dump())
        return result

    @app.get("/api/stats", response_model=Stats, dependencies=[Depends(require_dispatcher)])
    async def stats():
        """How many ambulances have the app registered, how many phones, and who is connected right now."""
        rows = []
        for s in store.summaries():
            devs = store.device_list(s.id)
            rows.append(VehicleStats(
                id=s.id,
                status=s.status,
                phones=len(devs),
                online=conns.is_online(s.id),
                first_registered=min((d.registered_at for d in devs), default=None),
                last_seen=max((d.last_seen for d in devs), default=None),
            ))
        return Stats(
            registered_vehicles=len(rows),
            registered_phones=sum(r.phones for r in rows),
            online_vehicles=conns.online_vehicle_count(),
            busy_vehicles=sum(1 for r in rows if r.status == Status.BUSY),
            vehicles=rows,
        )

    # ---------- phone ----------

    @app.post("/api/devices/register", response_model=RegisterOut)
    async def register(body: RegisterIn):
        """Called once when the driver enters the vehicle ID. The app stores the ID + token on the phone."""
        try:
            token, is_new = await store.register(body.ambulance_id)
        except NotAllowed:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "vehicle ID is not in the allowed fleet list")
        log.info("Phone registered for ambulance %s%s", body.ambulance_id, " (new ambulance)" if is_new else "")
        if is_new:
            await push_fleet()
        return RegisterOut(ambulance_id=body.ambulance_id, token=token)

    @app.post("/api/vehicles/{ambulance_id}/unregister", status_code=status.HTTP_204_NO_CONTENT)
    async def unregister(ambulance_id: int, token: str = Depends(device_token)):
        removed = await store.unregister(ambulance_id, token)
        log.info("Phone un-registered from ambulance %s%s", ambulance_id, " (ambulance removed)" if removed else "")
        if removed:
            await push_fleet()

    @app.get("/api/vehicles", response_model=list[VehicleSummary])
    async def list_vehicles():
        return store.summaries()

    @app.get("/api/vehicles/{ambulance_id}/assignment", response_model=Assignment | None)
    async def get_assignment(ambulance_id: int, _: str = Depends(device_token)):
        return store.assignment(ambulance_id)

    @app.post("/api/vehicles/{ambulance_id}/back-to-base", response_model=VehicleSummary)
    async def back_to_base(ambulance_id: int, _: str = Depends(device_token)):
        """Driver taps Back to Base. We call the dispatcher's endpoint; only if it succeeds does the
        ambulance become AVAILABLE. On failure it stays BUSY and the app retries."""
        vid = ambulance_id
        reason = await store.begin_release(vid)
        if reason == "already available":
            return store.summary(vid)  # idempotent: a repeated tap is harmless
        if reason:
            raise HTTPException(status.HTTP_409_CONFLICT, reason)

        ok, detail = False, "unexpected error"
        try:
            ok, detail = await notify_back_to_base(vid)
        finally:
            await store.finish_release(vid, ok)
        if not ok:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"back-to-base not confirmed: {detail}")

        log.info("Ambulance %s back to base (%s)", vid, detail)
        await conns.send_to_vehicle(vid, {"type": "assignment_cleared"})
        await push_status(vid)
        return store.summary(vid)

    @app.websocket("/ws")
    async def ws_endpoint(websocket: WebSocket, ambulance_id: int, token: str):
        if not store.token_valid(ambulance_id, token):
            await websocket.close(code=4403)
            return
        await websocket.accept()
        conns.add(ambulance_id, websocket)
        await store.touch(ambulance_id, token)
        try:
            # full resync on every (re)connect, so nothing is lost while offline
            assignment = store.assignment(ambulance_id)
            await websocket.send_json({
                "type": "snapshot",
                "vehicles": [s.model_dump(mode="json") for s in store.summaries()],
                "assignment": assignment.model_dump(mode="json") if assignment else None,
            })
            while True:
                await websocket.receive_text()  # keeps the socket open; app may send "ping"
        except WebSocketDisconnect:
            pass
        finally:
            conns.remove(ambulance_id, websocket)
            await store.touch(ambulance_id, token)

    @app.get("/download", include_in_schema=False)
    async def download_apk():
        """Open this URL in the phone's browser to install the app."""
        if not settings.apk_path.is_file():
            raise HTTPException(status.HTTP_404_NOT_FOUND, "APK not built yet")
        return FileResponse(settings.apk_path, media_type="application/vnd.android.package-archive",
                            filename="ambulance-dispatch.apk")

    @app.get("/health")
    async def health():
        return {"ok": True}

    return app


app = create_app()
