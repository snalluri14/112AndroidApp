"""Calls the dispatcher's Back-to-Base endpoint when a driver taps "Back to Base".

The request format is a placeholder until they share their spec:
POST {BACK_TO_BASE_URL}  body: {"ambulance_id": <id>}  header: X-API-Key (if configured)
"""
import logging

import httpx

from .config import settings

log = logging.getLogger(__name__)


async def notify_back_to_base(ambulance_id: int) -> tuple[bool, str]:
    """Returns (success, detail)."""
    if not settings.back_to_base_url:
        log.warning("BACK_TO_BASE_URL not set - skipping external call for %s (dev only)", ambulance_id)
        return True, "external call skipped (BACK_TO_BASE_URL not configured)"

    headers = {"X-API-Key": settings.back_to_base_api_key} if settings.back_to_base_api_key else {}
    try:
        async with httpx.AsyncClient(timeout=settings.back_to_base_timeout_s) as client:
            resp = await client.post(
                settings.back_to_base_url, json={"ambulance_id": ambulance_id}, headers=headers
            )
    except httpx.HTTPError as e:
        log.error("Back-to-Base call failed for %s: %s", ambulance_id, e)
        return False, f"could not reach dispatcher: {type(e).__name__}"

    if resp.is_success:
        return True, f"dispatcher responded {resp.status_code}"
    log.error("Back-to-Base rejected for %s: %s %s", ambulance_id, resp.status_code, resp.text[:200])
    return False, f"dispatcher responded {resp.status_code}"
