"""Settings read from environment variables (no config database)."""
import os
from pathlib import Path


def _parse_fleet(raw: str) -> list[int]:
    """Accepts '10000-10039' and/or comma lists like '10000,10001,10050-10055'."""
    ids: set[int] = set()
    for part in raw.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            start, end = (int(x) for x in part.split("-", 1))
            ids.update(range(start, end + 1))
        else:
            ids.add(int(part))
    return sorted(ids)


class Settings:
    def __init__(self) -> None:
        # Optional allow-list of vehicle IDs that may register. Empty (default) = any ID may register;
        # the fleet is simply whichever ambulances have registered a phone.
        self.fleet_ids: list[int] = _parse_fleet(os.getenv("FLEET_IDS", ""))

        # Shared key the dispatcher must send in the X-API-Key header. Empty = no check (dev only).
        self.dispatch_api_key: str = os.getenv("DISPATCH_API_KEY", "")

        # Dispatcher's Back-to-Base endpoint, called when the driver taps "Back to Base".
        # Empty = skip calling it and just mark AVAILABLE (dev only).
        self.back_to_base_url: str = os.getenv("BACK_TO_BASE_URL", "")
        self.back_to_base_api_key: str = os.getenv("BACK_TO_BASE_API_KEY", "")
        self.back_to_base_timeout_s: float = float(os.getenv("BACK_TO_BASE_TIMEOUT_S", "10"))

        # APK served at GET /download so phones can install it from a browser. Missing file = 404.
        self.apk_path: Path = Path(os.getenv(
            "APK_PATH", str(Path(__file__).resolve().parents[2] / "android/app/build/outputs/apk/debug/app-debug.apk")))

        # JSON snapshot file so a restart does not lose busy vehicles. Empty = memory only.
        state_file = os.getenv("STATE_FILE", "state.json")
        self.state_file: Path | None = Path(state_file) if state_file else None


settings = Settings()
