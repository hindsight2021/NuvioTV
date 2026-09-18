"""Device actions for Nuvio TV."""
from __future__ import annotations

from typing import Any
import voluptuous as vol

from homeassistant.const import CONF_DEVICE_ID, CONF_DOMAIN, CONF_TYPE
from homeassistant.core import Context, HomeAssistant
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.typing import ConfigType

from .const import DOMAIN, SERVICE_AI_COMMAND, SERVICE_OPEN_SCREEN, SERVICE_PLAY_THEMATIC_CHANNEL, SERVICE_SEND_DPAD

MEDIA_PLAYER_ACTIONS = {
    "play": "media_play",
    "pause": "media_pause",
    "stop": "media_stop",
}

NUVIO_ACTIONS = {
    "play_thematic_channel": "topic",
    "ai_command": "prompt",
    "open_screen": "screen",
    "send_dpad": "key",
}

ACTION_SCHEMA = cv.DEVICE_ACTION_BASE_SCHEMA.extend(
    {
        vol.Required(CONF_TYPE): str,
    },
    extra=vol.ALLOW_EXTRA,
)


async def async_get_actions(
    hass: HomeAssistant, device_id: str
) -> list[dict[str, Any]]:
    actions: list[dict[str, Any]] = []

    for action_type in MEDIA_PLAYER_ACTIONS:
        actions.append(
            {
                CONF_DEVICE_ID: device_id,
                CONF_DOMAIN: DOMAIN,
                CONF_TYPE: action_type,
            }
        )

    for action_type, field in NUVIO_ACTIONS.items():
        actions.append(
            {
                CONF_DEVICE_ID: device_id,
                CONF_DOMAIN: DOMAIN,
                CONF_TYPE: action_type,
                "field": field,
            }
        )

    return actions


async def async_call_action_from_config(
    hass: HomeAssistant,
    config: ConfigType,
    variables: dict[str, Any],
    context: Context | None,
) -> None:
    action_type = config[CONF_TYPE]
    device_id = config[CONF_DEVICE_ID]

    entity_id = _get_entity_id(hass, device_id)
    if entity_id is None:
        return

    if action_type in MEDIA_PLAYER_ACTIONS:
        await hass.services.async_call(
            "media_player",
            MEDIA_PLAYER_ACTIONS[action_type],
            {"entity_id": entity_id},
            blocking=True,
            context=context,
        )
        return

    field = NUVIO_ACTIONS.get(action_type)
    if field is None:
        return

    field_value = config.get(field)
    if field_value is None:
        return

    await hass.services.async_call(
        DOMAIN,
        action_type,
        {"entity_id": entity_id, field: field_value},
        blocking=True,
        context=context,
    )


def _get_entity_id(hass: HomeAssistant, device_id: str) -> str | None:
    from homeassistant.helpers import entity_registry as er
    entity_registry = er.async_get(hass)
    for entry in er.async_entries_for_device(entity_registry, device_id, include_disabled_entities=False):
        if entry.domain == "media_player" and (entry.platform == DOMAIN or entry.domain == DOMAIN):
            return entry.entity_id
    return None
