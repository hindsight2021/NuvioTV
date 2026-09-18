"""Device triggers for Nuvio TV."""
from __future__ import annotations

from typing import Any
import voluptuous as vol

from homeassistant.components.device_automation import DEVICE_TRIGGER_BASE_SCHEMA
from homeassistant.components.homeassistant.triggers import state as state_trigger
from homeassistant.const import (
    CONF_DEVICE_ID,
    CONF_DOMAIN,
    CONF_ENTITY_ID,
    CONF_PLATFORM,
    CONF_TYPE,
    STATE_BUFFERING,
    STATE_IDLE,
    STATE_OFF,
    STATE_PAUSED,
    STATE_PLAYING,
)
from homeassistant.core import CALLBACK_TYPE, HomeAssistant
from homeassistant.helpers.entity_registry import async_get as async_get_entity_registry
from homeassistant.helpers.typing import ConfigType

from .const import DOMAIN

TRIGGER_TYPES: dict[str, dict[str, Any]] = {
    "started_playing": {
        "from": None,
        "to": STATE_PLAYING,
    },
    "paused": {
        "from": None,
        "to": STATE_PAUSED,
    },
    "stopped": {
        "from": None,
        "to": [STATE_IDLE, STATE_OFF],
    },
    "buffering": {
        "from": None,
        "to": STATE_BUFFERING,
    },
}

TRIGGER_SCHEMA = DEVICE_TRIGGER_BASE_SCHEMA.extend(
    {
        vol.Required(CONF_TYPE): vol.In(TRIGGER_TYPES),
    }
)


async def async_get_triggers(
    hass: HomeAssistant, device_id: str
) -> list[dict[str, Any]]:
    entity_registry = async_get_entity_registry(hass)
    triggers: list[dict[str, Any]] = []

    for entry in entity_registry.entities.values():
        if entry.device_id != device_id or entry.domain != "media_player" or entry.platform != DOMAIN:
            continue

        for trigger_type in TRIGGER_TYPES:
            triggers.append(
                {
                    CONF_PLATFORM: "device",
                    CONF_DEVICE_ID: device_id,
                    CONF_DOMAIN: DOMAIN,
                    CONF_ENTITY_ID: entry.entity_id,
                    CONF_TYPE: trigger_type,
                }
            )

    return triggers


async def async_attach_trigger(
    hass: HomeAssistant,
    config: ConfigType,
    action: CALLBACK_TYPE,
    trigger_info: dict[str, Any],
) -> CALLBACK_TYPE:
    trigger_type: str = config[CONF_TYPE]
    trigger_spec = TRIGGER_TYPES[trigger_type]

    state_config = {
        CONF_PLATFORM: "state",
        CONF_ENTITY_ID: config[CONF_ENTITY_ID],
    }

    if trigger_spec["from"] is not None:
        state_config["from"] = trigger_spec["from"]
    if trigger_spec["to"] is not None:
        state_config["to"] = trigger_spec["to"]

    state_config = state_trigger.TRIGGER_SCHEMA(state_config)
    return await state_trigger.async_attach_trigger(
        hass,
        state_config,
        action,
        trigger_info,
        platform_type="device",
    )
