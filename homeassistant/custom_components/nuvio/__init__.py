"""The Nuvio TV integration."""
from __future__ import annotations

import logging
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT, Platform
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.aiohttp_client import async_get_clientsession
import voluptuous as vol

from .const import (
    ATTR_KEY,
    ATTR_PROMPT,
    ATTR_SCREEN,
    ATTR_TOPIC,
    CONF_TOKEN,
    DOMAIN,
    SERVICE_AI_COMMAND,
    SERVICE_OPEN_SCREEN,
    SERVICE_PLAY_THEMATIC_CHANNEL,
    SERVICE_SEND_DPAD,
)
from .coordinator import NuvioDataUpdateCoordinator
from .intent import async_setup_intents
from .nuvio_client import NuvioClient

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = [Platform.MEDIA_PLAYER]


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up Nuvio TV from a config entry."""
    hass.data.setdefault(DOMAIN, {})

    host = entry.data[CONF_HOST]
    port = entry.data[CONF_PORT]
    token = entry.data[CONF_TOKEN]

    session = async_get_clientsession(hass)
    client = NuvioClient(host=host, port=port, token=token, session=session)
    coordinator = NuvioDataUpdateCoordinator(hass, client)

    await coordinator.async_config_entry_first_refresh()

    hass.data[DOMAIN][entry.entry_id] = coordinator

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    # Register custom services and Assist voice intents once
    if len(hass.data[DOMAIN]) == 1:
        _register_services(hass)
        await async_setup_intents(hass)

    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a Nuvio config entry."""
    if unload_ok := await hass.config_entries.async_unload_platforms(entry, PLATFORMS):
        coordinator: NuvioDataUpdateCoordinator = hass.data[DOMAIN].pop(entry.entry_id)
        await coordinator.client.close()

    return unload_ok


def _register_services(hass: HomeAssistant) -> None:
    """Register custom Nuvio services."""

    async def handle_ai_command(call: ServiceCall) -> None:
        prompt = call.data[ATTR_PROMPT]
        entity_id = call.data.get("entity_id")
        coordinators = _get_coordinators(hass, entity_id)
        for coord in coordinators:
            await coord.client.ai_command(prompt)
            await coord.async_request_refresh()

    async def handle_thematic_channel(call: ServiceCall) -> None:
        topic = call.data[ATTR_TOPIC]
        entity_id = call.data.get("entity_id")
        coordinators = _get_coordinators(hass, entity_id)
        for coord in coordinators:
            await coord.client.play_thematic_channel(topic)
            await coord.async_request_refresh()

    async def handle_open_screen(call: ServiceCall) -> None:
        screen = call.data[ATTR_SCREEN]
        entity_id = call.data.get("entity_id")
        coordinators = _get_coordinators(hass, entity_id)
        for coord in coordinators:
            await coord.client.open_screen(screen)
            await coord.async_request_refresh()

    async def handle_send_dpad(call: ServiceCall) -> None:
        key = call.data[ATTR_KEY]
        entity_id = call.data.get("entity_id")
        coordinators = _get_coordinators(hass, entity_id)
        for coord in coordinators:
            await coord.client.send_dpad(key)
            await coord.async_request_refresh()

    hass.services.async_register(
        DOMAIN,
        SERVICE_AI_COMMAND,
        handle_ai_command,
        schema=vol.Schema({
            vol.Required(ATTR_PROMPT): cv.string,
            vol.Optional("entity_id"): cv.entity_ids,
        }),
    )

    hass.services.async_register(
        DOMAIN,
        SERVICE_PLAY_THEMATIC_CHANNEL,
        handle_thematic_channel,
        schema=vol.Schema({
            vol.Required(ATTR_TOPIC): cv.string,
            vol.Optional("entity_id"): cv.entity_ids,
        }),
    )

    hass.services.async_register(
        DOMAIN,
        SERVICE_OPEN_SCREEN,
        handle_open_screen,
        schema=vol.Schema({
            vol.Required(ATTR_SCREEN): cv.string,
            vol.Optional("entity_id"): cv.entity_ids,
        }),
    )

    hass.services.async_register(
        DOMAIN,
        SERVICE_SEND_DPAD,
        handle_send_dpad,
        schema=vol.Schema({
            vol.Required(ATTR_KEY): cv.string,
            vol.Optional("entity_id"): cv.entity_ids,
        }),
    )


def _get_coordinators(hass: HomeAssistant, entity_id: Any) -> list[NuvioDataUpdateCoordinator]:
    coordinators: list[NuvioDataUpdateCoordinator] = []
    domain_data = hass.data.get(DOMAIN, {})
    for entry_id, coordinator in domain_data.items():
        if isinstance(coordinator, NuvioDataUpdateCoordinator):
            coordinators.append(coordinator)
    return coordinators
