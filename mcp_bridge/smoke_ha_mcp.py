"""Read-only handshake check for the private Home Assistant MCP upstream."""

import asyncio
import os
from pathlib import Path

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


async def main():
    config = dict(os.environ)
    if "HA_MCP_URL" not in config:
        for line in Path("/etc/nuvio-ha-mcp.env").read_text().splitlines():
            key, value = line.split("=", 1)
            config[key] = value
    url = config["HA_MCP_URL"]
    authorization = config["HA_MCP_AUTHORIZATION"]
    async with httpx.AsyncClient(
        headers={"Authorization": authorization}, timeout=20, trust_env=False,
    ) as client:
        async with streamable_http_client(url, http_client=client) as streams:
            async with ClientSession(streams[0], streams[1]) as session:
                await session.initialize()
                tools = await session.list_tools()
                names = {tool.name for tool in tools.tools}
                print("handshake=ok tools=", len(names))
                print("required_tools=", sorted(names & {
                    "ha_get_state", "ha_get_overview", "ha_search", "ha_call_service",
                }))


if __name__ == "__main__":
    asyncio.run(main())
