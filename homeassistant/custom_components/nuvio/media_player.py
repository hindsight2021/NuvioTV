"""Media player platform for Nuvio TV."""
from __future__ import annotations

import logging
from typing import Any

from homeassistant.components.media_player import (
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
    MediaType,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity
from homeassistant.util import dt as dt_util

from .const import DOMAIN
from .coordinator import NuvioDataUpdateCoordinator
from .nuvio_client import NuvioClient

_LOGGER = logging.getLogger(__name__)

_STATE_MAP: dict[str, MediaPlayerState] = {
    "PLAYING": MediaPlayerState.PLAYING,
    "PAUSED": MediaPlayerState.PAUSED,
    "IDLE": MediaPlayerState.IDLE,
    "STOPPED": MediaPlayerState.IDLE,
    "BUFFERING": MediaPlayerState.BUFFERING,
    "ERROR": MediaPlayerState.IDLE,
}

NAV_SOURCES = ["Home", "Search", "Live TV", "Settings", "Library"]


async def async_setup_entry(
    hass: HomeAssistant,
    config_entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the Nuvio media player entity."""
    coordinator: NuvioDataUpdateCoordinator = hass.data[DOMAIN][config_entry.entry_id]
    async_add_entities([NuvioMediaPlayer(coordinator, config_entry)])


class NuvioMediaPlayer(CoordinatorEntity[NuvioDataUpdateCoordinator], MediaPlayerEntity):
    """Representation of a Nuvio TV Media Player."""

    _attr_device_class = MediaPlayerDeviceClass.TV
    _attr_has_entity_name = True
    _attr_name = None

    def __init__(
        self,
        coordinator: NuvioDataUpdateCoordinator,
        config_entry: ConfigEntry,
    ) -> None:
        super().__init__(coordinator)
        self._config_entry = config_entry
        self._attr_unique_id = f"{config_entry.entry_id}_media_player"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, config_entry.entry_id)},
            name=config_entry.title,
            manufacturer="Nuvio",
            model="Nuvio TV Player",
            sw_version=self._device_info_version(),
        )

    def _device_info_version(self) -> str:
        status = self.coordinator.data or {}
        device = status.get("device", {})
        return device.get("appVersion", "1.0.0")

    @property
    def client(self) -> NuvioClient:
        return self.coordinator.client

    @property
    def _playback(self) -> dict[str, Any]:
        status = self.coordinator.data or {}
        return status.get("playback", {})

    @property
    def _navigation(self) -> dict[str, Any]:
        status = self.coordinator.data or {}
        return status.get("navigation", {})

    @property
    def supported_features(self) -> MediaPlayerEntityFeature:
        return (
            MediaPlayerEntityFeature.PAUSE
            | MediaPlayerEntityFeature.PLAY
            | MediaPlayerEntityFeature.STOP
            | MediaPlayerEntityFeature.NEXT_TRACK
            | MediaPlayerEntityFeature.PREVIOUS_TRACK
            | MediaPlayerEntityFeature.SEEK
            | MediaPlayerEntityFeature.VOLUME_SET
            | MediaPlayerEntityFeature.SELECT_SOUND_MODE
            | MediaPlayerEntityFeature.SELECT_SOURCE
            | MediaPlayerEntityFeature.PLAY_MEDIA
        )

    @property
    def state(self) -> MediaPlayerState | None:
        if not self.coordinator.last_update_success:
            return MediaPlayerState.OFF
        raw_state = self._playback.get("state", "IDLE")
        return _STATE_MAP.get(str(raw_state).upper(), MediaPlayerState.IDLE)

    @property
    def media_title(self) -> str | None:
        return self._playback.get("title")

    @property
    def media_series_title(self) -> str | None:
        return self._playback.get("seriesTitle")

    @property
    def media_season(self) -> str | None:
        season = self._playback.get("seasonNumber")
        return str(season) if season is not None else None

    @property
    def media_episode(self) -> str | None:
        episode = self._playback.get("episodeNumber")
        return str(episode) if episode is not None else None

    @property
    def media_duration(self) -> int | None:
        duration_ms = self._playback.get("durationMs")
        if duration_ms is not None and duration_ms > 0:
            return int(duration_ms / 1000)
        return None

    @property
    def media_position(self) -> int | None:
        pos_ms = self._playback.get("positionMs")
        if pos_ms is not None and pos_ms >= 0:
            return int(pos_ms / 1000)
        return None

    @property
    def media_position_updated_at(self):
        if self.state in (MediaPlayerState.PLAYING, MediaPlayerState.PAUSED):
            return dt_util.utcnow()
        return None

    @property
    def volume_level(self) -> float | None:
        vol = self._playback.get("volume")
        if vol is not None:
            return float(vol)
        return None

    @property
    def sound_mode(self) -> str | None:
        active_id = self._playback.get("activeAudioTrackId")
        tracks = self._playback.get("audioTracks", [])
        for t in tracks:
            if t.get("id") == active_id:
                return t.get("label") or t.get("language") or active_id
        return None

    @property
    def sound_mode_list(self) -> list[str] | None:
        tracks = self._playback.get("audioTracks", [])
        if not tracks:
            return None
        return [t.get("label") or t.get("language") or t.get("id") for t in tracks]

    @property
    def source(self) -> str | None:
        current = self._navigation.get("currentScreen")
        if current:
            return current.replace("_", " ").title()
        return None

    @property
    def source_list(self) -> list[str]:
        return NAV_SOURCES

    async def async_media_play(self) -> None:
        await self.client.play()
        await self.coordinator.async_request_refresh()

    async def async_media_pause(self) -> None:
        await self.client.pause()
        await self.coordinator.async_request_refresh()

    async def async_media_stop(self) -> None:
        await self.client.stop()
        await self.coordinator.async_request_refresh()

    async def async_media_next_track(self) -> None:
        await self.client.skip_next()
        await self.coordinator.async_request_refresh()

    async def async_media_previous_track(self) -> None:
        await self.client.skip_previous()
        await self.coordinator.async_request_refresh()

    async def async_media_seek(self, position: float) -> None:
        await self.client.seek(int(position * 1000))
        await self.coordinator.async_request_refresh()

    async def async_set_volume_level(self, volume: float) -> None:
        await self.client.set_volume(volume)
        await self.coordinator.async_request_refresh()

    async def async_select_sound_mode(self, sound_mode: str) -> None:
        tracks = self._playback.get("audioTracks", [])
        for t in tracks:
            label = t.get("label") or t.get("language") or t.get("id")
            if label == sound_mode:
                await self.client.set_audio_track(t.get("id"))
                await self.coordinator.async_request_refresh()
                return

    async def async_select_source(self, source: str) -> None:
        mapped = source.lower().replace(" ", "_")
        await self.client.open_screen(mapped)
        await self.coordinator.async_request_refresh()

    async def async_play_media(
        self,
        media_type: MediaType | str,
        media_id: str,
        **kwargs: Any,
    ) -> None:
        m_type = "movie" if media_type == MediaType.MOVIE else "series"
        extra = kwargs.get("extra", {})
        season = extra.get("season")
        episode = extra.get("episode")
        await self.client.play_media(media_id, m_type, season, episode)
        await self.coordinator.async_request_refresh()

    async def async_ai_command(self, prompt: str) -> dict[str, Any]:
        res = await self.client.ai_command(prompt)
        await self.coordinator.async_request_refresh()
        return res

    async def async_play_thematic_channel(self, topic: str) -> None:
        await self.client.play_thematic_channel(topic)
        await self.coordinator.async_request_refresh()

    async def async_open_screen(self, screen: str) -> None:
        await self.client.open_screen(screen)
        await self.coordinator.async_request_refresh()

    async def async_send_dpad(self, key: str) -> None:
        await self.client.send_dpad(key)
        await self.coordinator.async_request_refresh()
