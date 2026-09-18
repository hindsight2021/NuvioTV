"""Constants for the Nuvio TV integration."""
from __future__ import annotations

from typing import Final

DOMAIN: Final = "nuvio"

CONF_HOST: Final = "host"
CONF_PORT: Final = "port"
CONF_PIN: Final = "pin"
CONF_TOKEN: Final = "token"
CONF_NAME: Final = "name"

DEFAULT_PORT: Final = 8910
DEFAULT_NAME: Final = "Nuvio TV"

SCAN_INTERVAL_SECONDS: Final = 2

# Custom service names
SERVICE_AI_COMMAND: Final = "ai_command"
SERVICE_PLAY_THEMATIC_CHANNEL: Final = "play_thematic_channel"
SERVICE_OPEN_SCREEN: Final = "open_screen"
SERVICE_SEND_DPAD: Final = "send_dpad"

ATTR_PROMPT: Final = "prompt"
ATTR_TOPIC: Final = "topic"
ATTR_SCREEN: Final = "screen"
ATTR_KEY: Final = "key"
