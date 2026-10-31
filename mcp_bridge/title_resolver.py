"""Resolve a spoken title and episode coordinates without touching the TV UI."""

from __future__ import annotations

import re
import secrets
from datetime import datetime, timezone
from difflib import SequenceMatcher
from typing import Any

import httpx

IMDB_ID = re.compile(r"^tt[0-9]{5,12}$")


def _name(value: str) -> str:
    value = re.sub(r"[^\w\s]", " ", value.casefold())
    value = " ".join(value.split())
    return re.sub(r"^(the|a|an) ", "", value)


def _year(value: Any) -> int | None:
    match = re.match(r"^(\d{4})", str(value or ""))
    return int(match.group(1)) if match else None


def _choose_title(hits: Any, title: str, content_type: str, year: int | None) -> dict[str, Any]:
    if not isinstance(hits, list):
        raise ValueError("Catalog returned invalid search results")
    candidates = [item for item in hits[:30] if isinstance(item, dict)
                  and item.get("type") == content_type
                  and isinstance(item.get("title"), str)
                  and isinstance(item.get("id"), str)]
    if year is not None:
        candidates = [item for item in candidates if _year(item.get("year")) == year]
    target = _name(title)
    exact = [item for item in candidates if _name(item["title"]) == target]
    if exact:
        return exact[0]  # The local catalog's ranking breaks same-title ties.
    ranked = sorted(((SequenceMatcher(None, target, _name(item["title"])).ratio(), item)
                     for item in candidates), key=lambda pair: pair[0], reverse=True)
    if ranked and ranked[0][0] >= 0.92 and (len(ranked) == 1 or ranked[0][0] - ranked[1][0] >= 0.08):
        return ranked[0][1]
    raise ValueError("No confident title match; no playback requested")


def _aired(video: dict[str, Any]) -> bool:
    raw = video.get("released") or video.get("firstAired")
    if not raw:
        return True
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00")) <= datetime.now(timezone.utc)
    except (ValueError, TypeError):
        return False


def _episodes(meta: Any, content_id: str) -> list[dict[str, Any]]:
    if not isinstance(meta, dict) or not isinstance(meta.get("meta"), dict):
        raise ValueError("Episode metadata is unavailable")
    item = meta["meta"]
    if item.get("id") != content_id or not isinstance(item.get("videos"), list):
        raise ValueError("Episode metadata does not match the selected title")
    episodes = []
    seen = set()
    for video in item["videos"][:2000]:
        if not isinstance(video, dict):
            continue
        season, episode = video.get("season"), video.get("episode", video.get("number"))
        if (type(season) is int and type(episode) is int and 0 <= season <= 100
                and 1 <= episode <= 1000 and _aired(video) and (season, episode) not in seen):
            seen.add((season, episode))
            episodes.append({"season": season, "episode": episode,
                             "episode_title": str(video.get("name") or "")[:200]})
    if not episodes:
        raise ValueError("No aired regular episodes found; no playback requested")
    return sorted(episodes, key=lambda item: (item["season"], item["episode"]))


async def resolve_target(client: httpx.AsyncClient, metadata_url: str, cinemeta_url: str,
                         title: str, content_type: str, selection: str = "first",
                         season: int | None = None, episode: int | None = None,
                         year: int | None = None) -> dict[str, Any]:
    """Return a verified play target; raise ValueError before any TV write."""
    if not isinstance(title, str) or not 1 <= len(title.strip()) <= 160:
        raise ValueError("title must be 1-160 characters")
    if content_type not in {"series", "movie"}:
        raise ValueError("content_type must be series or movie")
    if year is not None and (type(year) is not int or not 1880 <= year <= 2100):
        raise ValueError("year is invalid")
    if content_type == "series":
        if selection not in {"first", "random", "exact"}:
            raise ValueError("selection must be first, random or exact")
        if selection == "exact":
            if type(season) is not int or type(episode) is not int or not (0 <= season <= 100 and 1 <= episode <= 1000):
                raise ValueError("exact selection requires season and episode")
        elif episode is not None or (season is not None and (type(season) is not int or not 1 <= season <= 100)):
            raise ValueError("first/random accepts a season filter, not an episode")
    elif selection != "first" or season is not None or episode is not None:
        raise ValueError("movie cannot have episode selection")

    response = await client.get(f"{metadata_url}/api/nuvio/search", params={"q": title.strip()})
    response.raise_for_status()
    chosen = _choose_title(response.json(), title.strip(), content_type, year)
    content_id = chosen["id"]
    if not IMDB_ID.fullmatch(content_id):
        raise ValueError("Selected title has no supported IMDb ID; no playback requested")
    target = {"content_id": content_id, "content_type": content_type,
              "title": chosen["title"], "year": _year(chosen.get("year"))}
    if content_type == "movie":
        return target

    response = await client.get(f"{cinemeta_url}/meta/series/{content_id}.json")
    response.raise_for_status()
    if len(response.content) > 2_000_000:
        raise ValueError("Episode metadata is too large")
    pool = _episodes(response.json(), content_id)
    if selection == "exact":
        match = next((item for item in pool if item["season"] == season and item["episode"] == episode), None)
        if match is None:
            raise ValueError(f"S{season}E{episode} is not in the aired episode list; no playback requested")
    else:
        pool = [item for item in pool if item["season"] > 0]
        if season is not None:
            pool = [item for item in pool if item["season"] == season]
        if not pool:
            raise ValueError("No aired episodes in the requested season; no playback requested")
        match = pool[0] if selection == "first" else pool[secrets.randbelow(len(pool))]
    return {**target, **match, "selection": selection}
