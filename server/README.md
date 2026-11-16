# Ambulance Dispatch – Server

FastAPI server. No database: state is kept in memory and saved to `state.json`.

## Run

Requires Python 3.11+.

```
cd c:\Service\server
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Check it works: open http://localhost:8000/health → `{"ok":true}`

Stop: `Ctrl+C`. Registered phones and active dispatches are kept in `state.json` and restored on the next start.

## Settings (optional)

Set as environment variables before starting. All are empty by default.

| Variable | Purpose |
|---|---|
| `DISPATCH_API_KEY` | If set, the dispatcher must send header `X-API-Key: <key>` |
| `BACK_TO_BASE_URL` | Dispatcher's Back-to-Base endpoint. If empty, Back to Base just marks the ambulance AVAILABLE |
| `BACK_TO_BASE_API_KEY` | Sent as `X-API-Key` when calling their endpoint |
| `FLEET_IDS` | Only these IDs may register, e.g. `101-140,150` |

Example (PowerShell):
```
$env:BACK_TO_BASE_URL="https://their-system/back-to-base"
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## Install the app on a phone

Phone on the same network as this PC → open `http://<PC IP>:8000/download` in Chrome → install.
Find the PC IP with `ipconfig` (IPv4 Address under Wi-Fi).

If the phone can't connect: allow Python in Windows Firewall, and make sure the router doesn't block
devices from reaching each other (a phone hotspot works).

## Endpoints

| Method | Path | Used by |
|---|---|---|
| POST | `/api/dispatch` | Dispatcher – assign an ambulance |
| GET | `/download` | Download the APK |
| GET | `/docs` | Interactive API page for testing |

The other endpoints are used by the app.

## Send a test dispatch

Open http://localhost:8000/docs → `POST /api/dispatch` → **Try it out** → paste → **Execute**.
Use the vehicle ID entered on the phone.

```json
{
  "assignments": [{
    "ambulance_id": 101,
    "ambulance_lat": 16.3067,
    "ambulance_lon": 80.4365,
    "polyline": [[16.3067, 80.4365], [16.3042, 80.4414], [16.3018, 80.4441], [16.2988, 80.4481]],
    "hospital": {
      "lat": 16.2973, "lon": 80.4451, "name": "Govt General Hospital",
      "polyline": [[16.2988, 80.4481], [16.2980, 80.4466], [16.2973, 80.4451]]
    }
  }]
}
```

- `polyline`: route ambulance → patient (last point = patient). `hospital.polyline`: route patient → hospital.
- Response: `{"accepted": [101], "rejected": []}`.
- A BUSY ambulance is rejected until it returns to base. An ID with no registered phone is rejected.

## Tests

```
python -m pytest -q tests
```
