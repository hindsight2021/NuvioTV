"""Intent handlers for Nuvio voice control via Home Assistant Assist."""
from __future__ import annotations

import logging
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers import intent

from .const import (
    DOMAIN,
    SERVICE_AI_COMMAND,
    SERVICE_OPEN_SCREEN,
    SERVICE_PLAY_THEMATIC_CHANNEL,
    SERVICE_SEND_DPAD,
    ATTR_PROMPT,
    ATTR_TOPIC,
    ATTR_SCREEN,
    ATTR_KEY,
)

_LOGGER = logging.getLogger(__name__)

INTENT_PLAY_THEMATIC_CHANNEL = "NuvioPlayThematicChannel"
INTENT_AI_COMMAND = "NuvioAiCommand"
INTENT_OPEN_SCREEN = "NuvioOpenScreen"
INTENT_SEND_DPAD = "NuvioSendDpad"


def _get_slot_value(intent_obj: intent.Intent, slot_name: str) -> str | None:
    slot = intent_obj.slots.get(slot_name)
    if slot is None:
        return None
    val = slot.get("value")
    if val is None:
        return None
    val = str(val).strip()
    return val or None


class NuvioPlayThematicChannelIntent(intent.IntentHandler):
    intent_type = INTENT_PLAY_THEMATIC_CHANNEL
    description = "Play a thematic channel on Nuvio TV"
    slot_schema = {"topic": str}

    async def async_handle(self, intent_obj: intent.Intent) -> intent.IntentResponse:
        topic = _get_slot_value(intent_obj, "topic")
        if not topic:
            raise intent.IntentHandleError("No topic provided for thematic channel")

        await intent_obj.hass.services.async_call(
            DOMAIN,
            SERVICE_PLAY_THEMATIC_CHANNEL,
            {ATTR_TOPIC: topic},
            blocking=True,
        )

        response = intent_obj.create_response()
        response.async_set_speech(f"Starting {topic} channel on Nuvio TV")
        return response


class NuvioAiCommandIntent(intent.IntentHandler):
    intent_type = INTENT_AI_COMMAND
    description = "Send a natural language AI command to Nuvio TV"
    slot_schema = {"prompt": str}

    async def async_handle(self, intent_obj: intent.Intent) -> intent.IntentResponse:
        prompt = _get_slot_value(intent_obj, "prompt")
        if not prompt:
            raise intent.IntentHandleError("No prompt provided for AI command")

        await intent_obj.hass.services.async_call(
            DOMAIN,
            SERVICE_AI_COMMAND,
            {ATTR_PROMPT: prompt},
            blocking=True,
        )

        response = intent_obj.create_response()
        response.async_set_speech(f"Asking Nuvio TV to {prompt}")
        return response


class NuvioOpenScreenIntent(intent.IntentHandler):
    intent_type = INTENT_OPEN_SCREEN
    description = "Open a specific screen on Nuvio TV"
    slot_schema = {"screen": str}

    async def async_handle(self, intent_obj: intent.Intent) -> intent.IntentResponse:
        screen = _get_slot_value(intent_obj, "screen")
        if not screen:
            raise intent.IntentHandleError("No screen specified")

        await intent_obj.hass.services.async_call(
            DOMAIN,
            SERVICE_OPEN_SCREEN,
            {ATTR_SCREEN: screen.lower().replace(" ", "_")},
            blocking=True,
        )

        response = intent_obj.create_response()
        response.async_set_speech(f"Opening {screen} on Nuvio TV")
        return response


class NuvioSendDpadIntent(intent.IntentHandler):
    intent_type = INTENT_SEND_DPAD
    description = "Send navigation key to Nuvio TV"
    slot_schema = {"key": str}

    async def async_handle(self, intent_obj: intent.Intent) -> intent.IntentResponse:
        key = _get_slot_value(intent_obj, "key")
        if not key:
            raise intent.IntentHandleError("No key specified")

        mapped_key = key.lower().replace(" ", "_")
        if not mapped_key.startswith("dpad_") and mapped_key in ("up", "down", "left", "right", "center", "select"):
            mapped_key = f"dpad_{mapped_key}"
        if mapped_key == "dpad_select":
            mapped_key = "dpad_center"

        await intent_obj.hass.services.async_call(
            DOMAIN,
            SERVICE_SEND_DPAD,
            {ATTR_KEY: mapped_key},
            blocking=True,
        )

        response = intent_obj.create_response()
        response.async_set_speech(f"Sent {key} to Nuvio TV")
        return response


async def async_setup_intents(hass: HomeAssistant) -> None:
    """Register Nuvio Assist voice intents."""
    intent.async_register(hass, NuvioPlayThematicChannelIntent())
    intent.async_register(hass, NuvioAiCommandIntent())
    intent.async_register(hass, NuvioOpenScreenIntent())
    intent.async_register(hass, NuvioSendDpadIntent())
    _LOGGER.info("Registered Nuvio Assist voice intents")
