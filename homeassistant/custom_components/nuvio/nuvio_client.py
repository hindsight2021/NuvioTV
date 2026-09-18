"""Async client for the Nuvio REST API."""
from __future__ import annotations

import asyncio
from typing import Any, Dict, Optional

import aiohttp

DEFAULT_TIMEOUT = 10.0


class NuvioError(Exception):
    """Base exception for all Nuvio client errors."""


class NuvioConnectionError(NuvioError):
    """Raised when the client cannot reach the Nuvio device."""


class NuvioAuthError(NuvioError):
    """Raised when authentication with the Nuvio device fails."""


class NuvioClient:
    """Asynchronous client for the Nuvio REST API."""

    def __init__(
        self,
        host: str,
        port: int = 8910,
        token: str = "",
        session: Optional[aiohttp.ClientSession] = None,
        timeout: float = DEFAULT_TIMEOUT,
    ) -> None:
        self.host = host
        self.port = port
        self.token = token
        self._session = session
        self._owns_session = session is None
        self._timeout = aiohttp.ClientTimeout(total=timeout)

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    @property
    def _auth_headers(self) -> Dict[str, str]:
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or (self._owns_session and self._session.closed):
            self._session = aiohttp.ClientSession()
            self._owns_session = True
        return self._session

    async def close(self) -> None:
        if self._owns_session and self._session is not None and not self._session.closed:
            await self._session.close()
        self._session = None

    async def __aenter__(self) -> "NuvioClient":
        await self._get_session()
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        await self.close()

    async def _request(
        self,
        method: str,
        path: str,
        *,
        json: Optional[Dict[str, Any]] = None,
        auth: bool = True,
    ) -> Dict[str, Any]:
        session = await self._get_session()
        url = f"{self.base_url}{path}"
        headers = self._auth_headers if auth else {"Accept": "application/json"}

        try:
            async with session.request(
                method,
                url,
                json=json,
                headers=headers,
                timeout=self._timeout,
            ) as response:
                if response.status in (401, 403):
                    raise NuvioAuthError(f"Authentication failed ({response.status}) for {path}")

                if response.status >= 400:
                    text = await response.text()
                    raise NuvioError(f"Nuvio API error {response.status} for {path}: {text}")

                if response.content_length == 0:
                    return {}

                try:
                    return await response.json(content_type=None)
                except (aiohttp.ContentTypeError, ValueError):
                    text = await response.text()
                    return {"raw": text}

        except NuvioError:
            raise
        except asyncio.TimeoutError as err:
            raise NuvioConnectionError(f"Timed out contacting {url}") from err
        except aiohttp.ClientError as err:
            raise NuvioConnectionError(f"Failed to communicate with Nuvio at {url}: {err}") from err

    @classmethod
    async def pair(
        cls,
        host: str,
        port: int,
        pin: str,
        session: aiohttp.ClientSession,
        timeout: float = DEFAULT_TIMEOUT,
    ) -> Dict[str, Any]:
        """Pair with device using PIN."""
        url = f"http://{host}:{port}/api/v1/pair"
        client_timeout = aiohttp.ClientTimeout(total=timeout)
        try:
            async with session.post(
                url,
                json={"pin": pin},
                headers={"Accept": "application/json"},
                timeout=client_timeout,
            ) as response:
                if response.status in (401, 403):
                    raise NuvioAuthError("Invalid pairing PIN")
                if response.status >= 400:
                    text = await response.text()
                    raise NuvioError(f"Pairing failed with status {response.status}: {text}")
                return await response.json(content_type=None)
        except NuvioError:
            raise
        except asyncio.TimeoutError as err:
            raise NuvioConnectionError(f"Timed out pairing with {url}") from err
        except aiohttp.ClientError as err:
            raise NuvioConnectionError(f"Failed to pair with Nuvio at {url}: {err}") from err

    async def pair_instance(self, pin: str) -> str:
        session = await self._get_session()
        res = await self.pair(self.host, self.port, pin, session)
        self.token = res.get("token", "")
        return self.token

    async def get_status(self) -> Dict[str, Any]:
        return await self._request("GET", "/api/v1/status")

    async def play(self) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/playback/play")

    async def pause(self) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/playback/pause")

    async def play_pause(self) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/playback/play_pause")

    async def stop(self) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/playback/stop")

    async def seek(self, position_ms: int) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/playback/seek", json={"position_ms": int(position_ms)})

    async def skip_next(self) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/playback/skip_next")

    async def skip_previous(self) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/playback/skip_previous")

    async def set_volume(self, volume: float) -> Dict[str, Any]:
        clamped = max(0.0, min(1.0, float(volume)))
        return await self._request("POST", "/api/v1/playback/volume", json={"volume": clamped})

    async def set_audio_track(self, track_id: str) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/playback/tracks/audio", json={"track_id": track_id})

    async def set_subtitle_track(self, track_id: str) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/playback/tracks/subtitle", json={"track_id": track_id})

    async def send_dpad(self, key: str) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/navigation/dpad", json={"key": key})

    async def open_screen(self, screen: str) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/navigation/open", json={"screen": screen})

    async def search(self, query: str) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/navigation/search", json={"query": query})

    async def play_media(
        self,
        media_id: str,
        media_type: str = "movie",
        season: Optional[int] = None,
        episode: Optional[int] = None,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"media_id": media_id, "type": media_type}
        if season is not None:
            payload["season"] = int(season)
        if episode is not None:
            payload["episode"] = int(episode)
        return await self._request("POST", "/api/v1/play", json=payload)

    async def play_thematic_channel(self, topic: str) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/channels/thematic", json={"topic": topic})

    async def ai_command(self, prompt: str) -> Dict[str, Any]:
        return await self._request("POST", "/api/v1/ai/command", json={"prompt": prompt})
