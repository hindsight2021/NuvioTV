"""Private MCP adapter for the Nuvio+ REST control API.

Run on loopback. Tailscale Serve supplies private HTTPS; ChatGPT connects
through an OpenAI Secure MCP Tunnel, not directly to the tailnet address.
"""

from __future__ import annotations

import os
import re
from typing import Any

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from title_resolver import resolve_target

BASE_URL = os.environ.get("NUVIO_API_URL", "").rstrip("/")
TOKEN = os.environ.get("NUVIO_API_TOKEN", "")
PORT = int(os.environ.get("NUVIO_MCP_PORT", "8765"))
TAILSCALE_HOST = os.environ.get("NUVIO_MCP_TAILSCALE_HOST", "").strip()
METADATA_URL = os.environ.get("NUVIO_METADATA_URL", "http://192.168.1.4:8765").rstrip("/")
CINEMETA_URL = "https://v3-cinemeta.strem.io"
HA_URL = os.environ.get("HA_URL", "http://homeassistant.local:8123").rstrip("/")
HA_TOKEN = os.environ.get("HA_TOKEN", "")
HA_MCP_URL = os.environ.get("HA_MCP_URL", "")
HA_MCP_AUTHORIZATION = os.environ.get("HA_MCP_AUTHORIZATION", "")
DEFAULT_LIGHT_ENTITY = os.environ.get("NUVIO_LIGHT_ENTITY", "light.family_room")
if not BASE_URL.startswith(("http://", "https://")) or not TOKEN:
    raise RuntimeError("NUVIO_API_URL and NUVIO_API_TOKEN are required")

MCP = FastMCP(
    "Nuvio+",
    instructions=(
        "Use play_show_episode for a one-shot request by show title: selection "
        "first for 'play an episode', random for 'play a random episode', and "
        "exact with season and episode for SxEy. Do not ask the user to search "
        "on the TV. "
        "For 'resume <title>', call resume_continue_watching first; it uses the "
        "saved Continue Watching episode and does not substitute another. "
        "Use continue_watching for watching context and tv_calendar for the "
        "previous/upcoming Simkl schedule. "
        "Use play_movie_by_title for movies, or play_exact if a "
        "verified content ID is already known. search_catalog returns title "
        "matches; search_on_tv only opens the TV search screen. A play request "
        "marked RESOLVING is not playing; call "
        "play_request_status until STARTED, FAILED or CANCELLED. Never claim "
        "playback started from request acceptance alone. "
        "Use set_light_color (or set_light) to control light dimming (0-100%), "
        "fade duration (0-10s), and color for family room lights (or specified entity)."
    ),
    host="127.0.0.1",
    port=PORT,
    transport_security=TransportSecuritySettings(
        enable_dns_rebinding_protection=True,
        allowed_hosts=[
            "127.0.0.1:*", "localhost:*", "[::1]:*",
            *([TAILSCALE_HOST] if TAILSCALE_HOST else []),
        ],
        allowed_origins=[
            "http://127.0.0.1:*", "http://localhost:*", "http://[::1]:*",
            *([f"https://{TAILSCALE_HOST}"] if TAILSCALE_HOST else []),
        ],
    ),
)

ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,160}$")
REQUEST_RE = re.compile(r"^[0-9a-fA-F-]{36}$")


def _bounded(value: str, name: str, limit: int = 200) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise ValueError(f"{name} must be 1-{limit} characters")
    return value.strip()


def _identifier(value: str, name: str) -> str:
    value = _bounded(value, name, 160)
    if not ID_RE.fullmatch(value):
        raise ValueError(f"{name} has invalid characters")
    return value


def _request_id(value: str) -> str:
    value = _bounded(value, "request_id", 36)
    if not REQUEST_RE.fullmatch(value):
        raise ValueError("request_id must be a UUID")
    return value


async def _call(method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
    # The installed Nuvio+ build reads POST query parameters; the corrected
    # source also accepts JSON. Send both until the corrected APK is installed.
    headers = {"Authorization": f"Bearer {TOKEN}", "Accept": "application/json"}
    try:
        async with httpx.AsyncClient(
            base_url=BASE_URL,
            headers=headers,
            timeout=12,
            follow_redirects=False,
            trust_env=False,
        ) as client:
            response = await client.request(
                method, path, params=payload if method in {"GET", "POST"} else None,
                json=payload if method == "POST" else None,
            )
    except (httpx.TimeoutException, httpx.NetworkError, httpx.ProtocolError):
        return {"ok": False, "error": "Nuvio+ TV API is unavailable"}
    if response.status_code >= 400:
        return {"ok": False, "http_status": response.status_code,
                "error": "Nuvio+ TV API rejected the request"}
    try:
        return response.json()
    except ValueError:
        return {"ok": False, "error": "Nuvio+ returned invalid JSON"}


@MCP.tool(annotations={"readOnlyHint": True})
async def status() -> dict[str, Any]:
    """Read the current screen, player state and queue without changing playback."""
    return await _call("GET", "/api/v1/status")


@MCP.tool(annotations={"readOnlyHint": True})
async def queue() -> dict[str, Any]:
    """Read queued media without changing playback."""
    return await _call("GET", "/api/v1/queue")


@MCP.tool(annotations={"readOnlyHint": True})
async def continue_watching() -> dict[str, Any]:
    """List Nuvio+'s current Continue Watching items, exact episodes and saved positions."""
    return await _call("GET", "/api/v1/continue-watching")


@MCP.tool(annotations={"readOnlyHint": True})
async def tv_calendar(period: str = "both", days: int = 7) -> dict[str, Any]:
    """Read previous/upcoming Simkl TV episodes for shows in Nuvio's calendar."""
    if period not in {"upcoming", "previous", "both"}:
        raise ValueError("period must be upcoming, previous, or both")
    if type(days) is not int or not 1 <= days <= 7:
        raise ValueError("days must be an integer from 1 to 7")
    return await _call("GET", "/api/v1/calendar/tv",
                       {"period": period, "days": days})


@MCP.tool()
async def resume_continue_watching(title: str) -> dict[str, Any]:
    """Request the one matching in-progress title's exact episode; Nuvio restores saved progress.

    Call play_request_status to confirm playback. Ambiguous or absent titles do not play.
    """
    title = _bounded(title, "title", 160)
    listing = await continue_watching()
    if not isinstance(listing, dict) or not isinstance(listing.get("items"), list):
        return {"ok": False, "error": "Continue Watching is unavailable",
                "playback_requested": False}
    if listing.get("progressLoaded") is False:
        return {"ok": False, "error": "Continue Watching has not finished loading",
                "playback_requested": False}
    normalize = lambda value: re.sub(r"\s+", " ", value.casefold().strip())
    matches = [item for item in listing["items"] if isinstance(item, dict)
               and isinstance(item.get("title"), str)
               and normalize(item["title"]) == normalize(title)]
    if len(matches) != 1:
        return {"ok": False, "error": "Title is absent or ambiguous in Continue Watching",
                "matches": [{"title": item.get("title"), "season": item.get("season"),
                             "episode": item.get("episode")} for item in matches[:10]],
                "playback_requested": False}
    item = matches[0]
    kind = item.get("contentType")
    season, episode = item.get("season"), item.get("episode")
    if kind == "series" and (type(season) is not int or type(episode) is not int):
        return {"ok": False, "error": "Saved episode coordinates are missing",
                "playback_requested": False}
    if kind not in {"series", "movie"}:
        return {"ok": False, "error": "Unsupported saved content type",
                "playback_requested": False}
    result = await play_exact(item.get("contentId", ""), kind,
                              season if kind == "series" else None,
                              episode if kind == "series" else None)
    return {"saved_item": item, **result}


@MCP.tool()
async def search_on_tv(query: str) -> dict[str, Any]:
    """Open Nuvio+'s search screen with a query; does not return search results."""
    return await _call("POST", "/api/v1/navigation/search",
                       {"query": _bounded(query, "query")})


async def _resolve_and_play(title: str, content_type: str, selection: str = "first",
                            season: int | None = None, episode: int | None = None,
                            year: int | None = None) -> dict[str, Any]:
    if content_type == "series" and not title.strip():
        current = await status()
        playback_item = current.get("playback") or {}
        queue_item = (current.get("queue") or {}).get("currentItem") or {}
        if playback_item.get("contentType") == "series":
            title = playback_item.get("contentName") or ""
        elif queue_item.get("mediaType") == "series":
            title = queue_item.get("seriesTitle") or queue_item.get("title") or ""
        if not title:
            return {"ok": False, "error": "No current series is known; no playback requested",
                    "playback_requested": False}
    try:
        async with httpx.AsyncClient(timeout=10, follow_redirects=False,
                                     trust_env=False) as client:
            target = await resolve_target(client, METADATA_URL, CINEMETA_URL,
                                          title, content_type, selection,
                                          season, episode, year)
    except ValueError as error:
        return {"ok": False, "error": str(error), "playback_requested": False}
    except (httpx.HTTPError, httpx.TimeoutException):
        return {"ok": False, "error": "Title or episode metadata is unavailable",
                "playback_requested": False}
    result = await play_exact(target["content_id"], content_type,
                              target.get("season"), target.get("episode"))
    return {"selected": target, **result}


@MCP.tool(annotations={"readOnlyHint": True})
async def search_catalog(query: str, content_type: str = "series") -> dict[str, Any]:
    """Return real catalog matches by title; does not change the TV screen."""
    query = _bounded(query, "query", 160)
    if content_type not in {"series", "movie"}:
        raise ValueError("content_type must be series or movie")
    try:
        async with httpx.AsyncClient(timeout=10, follow_redirects=False,
                                     trust_env=False) as client:
            response = await client.get(f"{METADATA_URL}/api/nuvio/search",
                                        params={"q": query})
            response.raise_for_status()
            hits = response.json()
        if not isinstance(hits, list):
            raise ValueError("Invalid catalog response")
        return {"query": query, "results": [
            {"id": item.get("id"), "title": item.get("title"),
             "type": item.get("type"), "year": item.get("year")}
            for item in hits[:20] if isinstance(item, dict)
            and item.get("type") == content_type]}
    except (httpx.HTTPError, ValueError):
        return {"ok": False, "error": "Catalog search is unavailable"}


@MCP.tool()
async def play_show_episode(title: str = "", selection: str = "first",
                            season: int | None = None, episode: int | None = None,
                            year: int | None = None) -> dict[str, Any]:
    """Play a show episode by title in one call, without an on-TV search.

    Omit title only to use the currently playing or queued series, if known.
    selection='first' plays the earliest aired regular episode (or first in
    season). selection='random' selects uniformly from aired regular episodes.
    selection='exact' requires season and episode and never substitutes another
    episode. year can distinguish same-name shows. Returns the chosen title and
    episode plus an asynchronous TV request; use play_request_status to confirm
    STARTED. No follow-up input is needed for a confident catalog match.
    """
    return await _resolve_and_play(title, "series", selection, season, episode, year)


@MCP.tool()
async def play_movie_by_title(title: str, year: int | None = None) -> dict[str, Any]:
    """Resolve a movie title and request playback directly, without TV search."""
    return await _resolve_and_play(title, "movie", year=year)


@MCP.tool()
async def play_exact(content_id: str, content_type: str,
                     season: int | None = None, episode: int | None = None) -> dict[str, Any]:
    """Request an exact movie or series episode by verified Nuvio content ID.

    Returns RESOLVING on acceptance. Call play_request_status for actual outcome.
    """
    content_id = _identifier(content_id, "content_id")
    if content_type not in ("movie", "series"):
        raise ValueError("content_type must be movie or series")
    if content_type == "movie" and (season is not None or episode is not None):
        raise ValueError("movie cannot have season or episode")
    if (season is None) != (episode is None):
        raise ValueError("season and episode must be supplied together")
    if season is not None:
        if content_type != "series" or isinstance(season, bool) or isinstance(episode, bool):
            raise ValueError("invalid episode coordinates")
        if not (0 <= season <= 100 and 1 <= episode <= 1000):
            raise ValueError("season or episode outside allowed range")
    payload: dict[str, Any] = {"contentId": content_id, "type": content_type}
    if season is not None:
        payload.update(season=season, episode=episode)
    result = await _call("POST", "/api/v1/play", payload)
    if isinstance(result, dict) and result.get("status") == "ok":
        return {"request_accepted": True, "playback_started": False,
                "request": result.get("data"), "message": result.get("message")}
    return result


@MCP.tool(annotations={"readOnlyHint": True})
async def play_request_status(request_id: str) -> dict[str, Any]:
    """Read an exact play request: RESOLVING, STARTED, FAILED or CANCELLED."""
    return await _call("GET", f"/api/v1/play/requests/{_request_id(request_id)}")


@MCP.tool()
async def cancel_play_request(request_id: str) -> dict[str, Any]:
    """Cancel a pending exact play request."""
    return await _call("DELETE", f"/api/v1/play/requests/{_request_id(request_id)}")


@MCP.tool()
async def playback(action: str) -> dict[str, Any]:
    """Control playback: play, pause, play_pause, stop, next, previous, skip_intro."""
    if action not in {"play", "pause", "play_pause", "stop",
                      "next", "previous", "skip_intro"}:
        raise ValueError("unsupported playback action")
    return await _call("POST", f"/api/v1/playback/{action}")


@MCP.tool()
async def seek(position_ms: int) -> dict[str, Any]:
    """Seek to an absolute position in milliseconds (0 to 24 hours)."""
    if isinstance(position_ms, bool) or not isinstance(position_ms, int):
        raise ValueError("position_ms must be an integer")
    if not 0 <= position_ms <= 86_400_000:
        raise ValueError("position_ms outside allowed range")
    return await _call("POST", "/api/v1/playback/seek", {"positionMs": position_ms})


@MCP.tool()
async def seek_by(delta_ms: int) -> dict[str, Any]:
    """Seek relative to the current position by up to 4 hours forward or back."""
    if type(delta_ms) is not int or not -14_400_000 <= delta_ms <= 14_400_000:
        raise ValueError("delta_ms must be an integer within four hours")
    return await _call("POST", "/api/v1/playback/seek", {"deltaMs": delta_ms})


@MCP.tool()
async def set_speed(speed: float) -> dict[str, Any]:
    """Set playback speed from 0.25x to 3x."""
    if isinstance(speed, bool) or not isinstance(speed, (float, int)) or not 0.25 <= speed <= 3.0:
        raise ValueError("speed must be between 0.25 and 3.0")
    return await _call("POST", "/api/v1/playback/speed", {"speed": speed})


@MCP.tool()
async def select_track(kind: str, index: int) -> dict[str, Any]:
    """Select an audio or subtitle track by index from the current status."""
    if kind not in ("audio", "subtitle") or isinstance(index, bool) or not isinstance(index, int):
        raise ValueError("kind must be audio/subtitle and index must be integer")
    if not 0 <= index <= 64:
        raise ValueError("index outside allowed range")
    return await _call("POST", f"/api/v1/playback/{kind}_track", {"index": index})


@MCP.tool()
async def disable_subtitles() -> dict[str, Any]:
    """Turn off subtitles for the current player session."""
    return await _call("POST", "/api/v1/playback/subtitles/disable")


@MCP.tool()
async def dpad(key: str) -> dict[str, Any]:
    """Send one TV remote key: up, down, left, right, select, back or menu."""
    if key not in {"up", "down", "left", "right", "select", "back", "menu"}:
        raise ValueError("unsupported remote key")
    return await _call("POST", "/api/v1/navigation/dpad", {"key": key})


@MCP.tool()
async def open_screen(screen: str) -> dict[str, Any]:
    """Open a Nuvio+ screen: home, search, library, live_tv, calendar or settings."""
    if screen not in {"home", "search", "library", "live_tv", "calendar", "settings"}:
        raise ValueError("unsupported screen")
    return await _call("POST", "/api/v1/navigation/open", {"screen": screen})


@MCP.tool()
async def clear_queue() -> dict[str, Any]:
    """Clear the Nuvio+ playback queue."""
    return await _call("DELETE", "/api/v1/queue")


@MCP.tool()
async def add_to_queue(
    title: str,
    content_type: str = "movie",
    season: int | None = None,
    episode: int | None = None,
    year: int | None = None,
    content_id: str | None = None,
    video_id: str | None = None
) -> dict[str, Any]:
    """Append a movie or series episode to the Nuvio+ playback queue."""
    if content_id:
        target = {
            "content_id": content_id,
            "title": title,
            "season": season,
            "episode": episode,
            "video_id": video_id or (f"{content_id}:{season}:{episode}" if season and episode else content_id)
        }
    else:
        selection = "exact" if (season is not None and episode is not None) else "first"
        try:
            async with httpx.AsyncClient(timeout=10, follow_redirects=False, trust_env=False) as client:
                target = await resolve_target(client, METADATA_URL, CINEMETA_URL,
                                              title, content_type, selection,
                                              season, episode, year)
        except (ValueError, httpx.HTTPError, httpx.TimeoutException):
            target = {
                "content_id": f"queue_{title.lower().replace(' ', '_')}",
                "title": title,
                "season": season,
                "episode": episode,
                "video_id": video_id
            }

    payload = {
        "contentId": target.get("content_id", ""),
        "videoId": target.get("video_id") or target.get("content_id"),
        "title": target.get("title", title),
        "season": target.get("season"),
        "episode": target.get("episode"),
        "mediaType": content_type,
        "playNext": False
    }
    res = await _call("POST", "/api/v1/queue", payload)
    return {"queued": target, **res}


@MCP.tool()
async def play_next(
    title: str,
    content_type: str = "movie",
    season: int | None = None,
    episode: int | None = None,
    year: int | None = None,
    content_id: str | None = None,
    video_id: str | None = None
) -> dict[str, Any]:
    """Insert a movie or series episode to play next immediately after the current item."""
    if content_id:
        target = {
            "content_id": content_id,
            "title": title,
            "season": season,
            "episode": episode,
            "video_id": video_id or (f"{content_id}:{season}:{episode}" if season and episode else content_id)
        }
    else:
        selection = "exact" if (season is not None and episode is not None) else "first"
        try:
            async with httpx.AsyncClient(timeout=10, follow_redirects=False, trust_env=False) as client:
                target = await resolve_target(client, METADATA_URL, CINEMETA_URL,
                                              title, content_type, selection,
                                              season, episode, year)
        except (ValueError, httpx.HTTPError, httpx.TimeoutException):
            target = {
                "content_id": f"queue_{title.lower().replace(' ', '_')}",
                "title": title,
                "season": season,
                "episode": episode,
                "video_id": video_id
            }

    payload = {
        "contentId": target.get("content_id", ""),
        "videoId": target.get("video_id") or target.get("content_id"),
        "title": target.get("title", title),
        "season": target.get("season"),
        "episode": target.get("episode"),
        "mediaType": content_type,
        "playNext": True
    }
    res = await _call("POST", "/api/v1/queue/play-next", payload)
    return {"play_next": target, **res}


@MCP.tool()
async def advance_queue() -> dict[str, Any]:
    """Skip to the next item in the Nuvio+ playback queue."""
    return await _call("POST", "/api/v1/queue/advance")


_HEX_COLOR_RE = re.compile(r"^#?([0-9a-fA-F]{6})$")


def _parse_hex_color(color: str) -> list[int] | None:
    """Parse a '#RRGGBB' or 'RRGGBB' string into an [r, g, b] list."""
    match = _HEX_COLOR_RE.match(color.strip())
    if not match:
        return None
    hex_digits = match.group(1)
    return [
        int(hex_digits[0:2], 16),
        int(hex_digits[2:4], 16),
        int(hex_digits[4:6], 16),
    ]


async def _call_ha_light(
    entity_id: str,
    brightness_pct: int,
    transition: int | float,
    color: str | None = None,
) -> dict[str, Any]:
    """Send a light command to Home Assistant."""
    if not isinstance(brightness_pct, (int, float)) or not (0 <= brightness_pct <= 100):
        return {
            "ok": False,
            "error": f"brightness_pct must be between 0 and 100, got {brightness_pct!r}",
        }
    brightness_pct = int(brightness_pct)

    if not isinstance(transition, (int, float)) or not (0.0 <= float(transition) <= 10.0):
        return {
            "ok": False,
            "error": f"transition must be between 0.0 and 10.0 seconds, got {transition!r}",
        }
    transition = float(transition)

    if not re.fullmatch(r"light\.[a-z0-9_]+", entity_id):
        return {"ok": False, "error": "entity_id must identify a light"}

    if not (HA_MCP_URL and HA_MCP_AUTHORIZATION) and not HA_TOKEN:
        return {"ok": False, "error": "Home Assistant credentials are not configured"}

    if brightness_pct == 0:
        service = "turn_off"
        service_data: dict[str, Any] = {
            "entity_id": entity_id,
            "transition": transition,
        }
    else:
        service = "turn_on"
        service_data = {
            "entity_id": entity_id,
            "brightness_pct": brightness_pct,
            "transition": transition,
        }
        if color is not None and color.strip():
            rgb = _parse_hex_color(color)
            if rgb is not None:
                service_data["rgb_color"] = rgb
            else:
                service_data["color_name"] = color.strip().lower()

    if HA_MCP_URL and HA_MCP_AUTHORIZATION:
        try:
            async with httpx.AsyncClient(
                headers={"Authorization": HA_MCP_AUTHORIZATION}, timeout=20.0,
                trust_env=False,
            ) as client:
                async with streamable_http_client(HA_MCP_URL, http_client=client) as streams:
                    async with ClientSession(streams[0], streams[1]) as session:
                        await session.initialize()
                        result = await session.call_tool("ha_call_service", {
                            "domain": "light", "service": service,
                            "entity_id": entity_id,
                            "data": {k: v for k, v in service_data.items() if k != "entity_id"},
                        })
            payload = result.structuredContent
            if payload is None and result.content and result.content[0].type == "text":
                import json
                payload = json.loads(result.content[0].text)
            if result.isError or not isinstance(payload, dict) or payload.get("success") is not True:
                return {"ok": False, "error": "Home Assistant rejected the light command"}
            return {"ok": True, "service": service, "entity_id": entity_id,
                    "service_data": service_data}
        except Exception:
            return {"ok": False, "error": "Home Assistant light command failed"}

    url = f"{HA_URL}/api/services/light/{service}"
    headers = {
        "Authorization": f"Bearer {HA_TOKEN}",
        "Content-Type": "application/json",
    }

    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            response = await client.post(url, headers=headers, json=service_data)
            response.raise_for_status()
    except httpx.HTTPError as exc:
        return {
            "ok": False,
            "service": service,
            "entity_id": entity_id,
            "service_data": service_data,
            "error": "HTTP error calling Home Assistant",
        }
    except Exception as exc:
        return {
            "ok": False,
            "service": service,
            "entity_id": entity_id,
            "service_data": service_data,
            "error": "Unexpected error calling Home Assistant",
        }

    return {
        "ok": True,
        "service": service,
        "entity_id": entity_id,
        "service_data": service_data,
    }


@MCP.tool()
async def set_light_color(
    brightness_pct: int = 20,
    transition_seconds: float = 7.0,
    color: str | None = None,
    entity_id: str | None = None,
) -> dict[str, Any]:
    """Set light brightness (0-100%), transition duration (0-10s), and optional color.

    Defaults to the family room lights (light.family_room) when no entity is specified.
    """
    target_entity = entity_id.strip() if entity_id and entity_id.strip() else DEFAULT_LIGHT_ENTITY
    try:
        brightness = int(brightness_pct)
    except (TypeError, ValueError):
        brightness = 20
    brightness = max(0, min(100, brightness))

    try:
        transition = float(transition_seconds)
    except (TypeError, ValueError):
        transition = 7.0
    transition = max(0.0, min(10.0, transition))

    return await _call_ha_light(
        entity_id=target_entity,
        brightness_pct=brightness,
        transition=transition,
        color=color,
    )


@MCP.tool()
async def set_light(
    brightness_pct: int = 20,
    transition_seconds: float = 7.0,
    color: str | None = None,
    entity_id: str | None = None,
) -> dict[str, Any]:
    """Alias for set_light_color to control brightness, transition, and color."""
    return await set_light_color(
        brightness_pct=brightness_pct,
        transition_seconds=transition_seconds,
        color=color,
        entity_id=entity_id,
    )


if __name__ == "__main__":
    MCP.run(transport="streamable-http")
