"""Config flow for the Nuvio TV integration."""
from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol

from homeassistant import config_entries
from homeassistant.const import CONF_HOST, CONF_NAME, CONF_PORT
from homeassistant.data_entry_flow import FlowResult
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .const import CONF_PIN, CONF_TOKEN, DEFAULT_NAME, DEFAULT_PORT, DOMAIN
from .nuvio_client import (
    NuvioAuthError,
    NuvioClient,
    NuvioConnectionError,
    NuvioError,
)

_LOGGER = logging.getLogger(__name__)

STEP_USER_DATA_SCHEMA = vol.Schema(
    {
        vol.Required(CONF_HOST): str,
        vol.Optional(CONF_PORT, default=DEFAULT_PORT): vol.All(
            vol.Coerce(int), vol.Range(min=1, max=65535)
        ),
        vol.Required(CONF_PIN): str,
        vol.Optional(CONF_NAME, default=DEFAULT_NAME): str,
    }
)

STEP_REAUTH_DATA_SCHEMA = vol.Schema(
    {
        vol.Required(CONF_PIN): str,
    }
)


class NuvioConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle a config flow for Nuvio TV."""

    VERSION = 1

    def __init__(self) -> None:
        self._host: str | None = None
        self._port: int = DEFAULT_PORT
        self._name: str = DEFAULT_NAME
        self._reauth_entry: config_entries.ConfigEntry | None = None

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        errors: dict[str, str] = {}

        if user_input is not None:
            host = user_input[CONF_HOST].strip()
            port = user_input[CONF_PORT]
            pin = user_input[CONF_PIN].strip()
            name = user_input.get(CONF_NAME, DEFAULT_NAME).strip() or DEFAULT_NAME

            await self.async_set_unique_id(f"{host}:{port}")
            self._abort_if_unique_id_configured()

            session = async_get_clientsession(self.hass)
            try:
                pair_result = await NuvioClient.pair(host, port, pin, session)
                token = pair_result.get("token")
                device_name = pair_result.get("device_name", name)
                if not token:
                    raise NuvioAuthError("No token in pair response")

                return self.async_create_entry(
                    title=device_name,
                    data={
                        CONF_HOST: host,
                        CONF_PORT: port,
                        CONF_NAME: device_name,
                        CONF_TOKEN: token,
                    },
                )
            except NuvioAuthError:
                errors["base"] = "invalid_auth"
            except NuvioConnectionError:
                errors["base"] = "cannot_connect"
            except Exception:
                _LOGGER.exception("Unexpected error during Nuvio pairing")
                errors["base"] = "unknown"

        return self.async_show_form(
            step_id="user",
            data_schema=STEP_USER_DATA_SCHEMA,
            errors=errors,
        )

    async def async_step_reauth(
        self, entry_data: dict[str, Any]
    ) -> FlowResult:
        self._reauth_entry = self.hass.config_entries.async_get_entry(
            self.context["entry_id"]
        )
        if self._reauth_entry is None:
            return self.async_abort(reason="reauth_entry_not_found")

        self._host = self._reauth_entry.data[CONF_HOST]
        self._port = self._reauth_entry.data[CONF_PORT]
        self._name = self._reauth_entry.data.get(CONF_NAME, DEFAULT_NAME)

        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        errors: dict[str, str] = {}

        if user_input is not None:
            pin = user_input[CONF_PIN].strip()
            session = async_get_clientsession(self.hass)
            try:
                pair_result = await NuvioClient.pair(self._host, self._port, pin, session)
                token = pair_result.get("token")
                if not token:
                    raise NuvioAuthError("No token returned")

                if self._reauth_entry is not None:
                    self.hass.config_entries.async_update_entry(
                        self._reauth_entry,
                        data={
                            **self._reauth_entry.data,
                            CONF_TOKEN: token,
                        },
                    )
                return self.async_abort(reason="reauth_successful")
            except NuvioAuthError:
                errors["base"] = "invalid_auth"
            except NuvioConnectionError:
                errors["base"] = "cannot_connect"
            except Exception:
                errors["base"] = "unknown"

        return self.async_show_form(
            step_id="reauth_confirm",
            data_schema=STEP_REAUTH_DATA_SCHEMA,
            errors=errors,
            description_placeholders={
                CONF_HOST: self._host or "",
                CONF_PORT: str(self._port),
            },
        )
