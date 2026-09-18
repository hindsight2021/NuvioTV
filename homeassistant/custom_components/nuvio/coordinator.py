"""Data update coordinator for Nuvio TV."""
from __future__ import annotations

from datetime import timedelta
import logging
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .const import DOMAIN, SCAN_INTERVAL_SECONDS
from .nuvio_client import (
    NuvioAuthError,
    NuvioClient,
    NuvioConnectionError,
    NuvioError,
)

_LOGGER = logging.getLogger(__name__)


class NuvioDataUpdateCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Coordinator to manage fetching Nuvio status."""

    def __init__(self, hass: HomeAssistant, client: NuvioClient) -> None:
        self.client = client
        super().__init__(
            hass,
            _LOGGER,
            name=DOMAIN,
            update_interval=timedelta(seconds=SCAN_INTERVAL_SECONDS),
        )

    async def _async_update_data(self) -> dict[str, Any]:
        try:
            return await self.client.get_status()
        except NuvioAuthError as err:
            raise ConfigEntryAuthFailed(f"Authentication with Nuvio failed: {err}") from err
        except NuvioConnectionError as err:
            raise UpdateFailed(f"Error communicating with Nuvio: {err}") from err
        except NuvioError as err:
            raise UpdateFailed(f"Unexpected Nuvio API error: {err}") from err
