"""Reconcile three fixed Pi MCP service groups from Home Assistant switches.

Runs outside the controlled units. Unknown HA state leaves the last systemd
state unchanged; disabled units remain disabled across Pi restarts.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import subprocess

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


GROUPS = {
    "input_boolean.nuvio_mcp_enabled": (
        "nuvio-mcp-bridge.service", "nuvio-mcp-tunnel.service"),
    "input_boolean.home_assistant_mcp_enabled": (
        "nuvio-ha-mcp-tunnel.service",),
    "input_boolean.radio_operations_mcp_enabled": (
        "radio-ops-mcp.service", "radio-ops-mcp-tunnel.service"),
}


def _payload(result):
    data = result.structuredContent
    if data is None and result.content and result.content[0].type == "text":
        data = json.loads(result.content[0].text)
    if result.isError or not isinstance(data, dict):
        raise RuntimeError("Home Assistant MCP state read failed")
    return data


def _state(data):
    # ha_get_state exposes the state under data.state on this MCP server.
    record = data.get("data", data)
    return record.get("state") if isinstance(record, dict) else None


def _systemctl(*args):
    return subprocess.run(["/usr/bin/systemctl", *args],
                          check=args[0] not in {"is-enabled", "is-active"},
                          capture_output=True, text=True)


def _reconcile(units, enabled):
    for unit in units:
        if enabled:
            if _systemctl("is-enabled", unit).stdout.strip() != "enabled":
                _systemctl("enable", unit)
            if _systemctl("is-active", unit).stdout.strip() != "active":
                _systemctl("start", unit)
        else:
            # Stop first, then disable so a reboot cannot reopen a tunnel.
            if _systemctl("is-active", unit).stdout.strip() == "active":
                _systemctl("stop", unit)
            if _systemctl("is-enabled", unit).stdout.strip() == "enabled":
                _systemctl("disable", unit)


async def poll_once():
    async with httpx.AsyncClient(
        headers={"Authorization": os.environ["HA_MCP_AUTHORIZATION"]},
        timeout=20, trust_env=False,
    ) as client:
        async with streamable_http_client(os.environ["HA_MCP_URL"], http_client=client) as streams:
            async with ClientSession(streams[0], streams[1]) as session:
                await session.initialize()
                for entity, units in GROUPS.items():
                    result = await session.call_tool("ha_get_state", {"entity_id": entity})
                    state = _state(_payload(result))
                    if state not in {"on", "off"}:
                        logging.warning("Ignoring unknown state for %s", entity)
                        continue
                    if os.environ.get("MCP_GUARD_DRY_RUN") == "1":
                        logging.info("dry-run %s=%s", entity, state)
                    else:
                        _reconcile(units, state == "on")


async def main():
    logging.basicConfig(level=logging.INFO)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("mcp.client.streamable_http").setLevel(logging.WARNING)
    while True:
        try:
            await poll_once()
        except Exception:
            logging.error("MCP guard poll failed; preserving current unit state")
        await asyncio.sleep(15)


if __name__ == "__main__":
    asyncio.run(main())
