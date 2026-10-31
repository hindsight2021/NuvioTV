"""Read-only local MCP tool registration check."""

import asyncio

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


async def main():
    async with streamable_http_client("http://127.0.0.1:8765/mcp") as streams:
        async with ClientSession(streams[0], streams[1]) as session:
            await session.initialize()
            listed = await session.list_tools()
            names = {tool.name for tool in listed.tools}
            required = {"continue_watching", "resume_continue_watching", "tv_calendar"}
            print("tool_count=", len(names))
            print("new_tools_registered=", sorted(required & names))
            if not required <= names:
                raise SystemExit("new tools missing")
            for name in ("continue_watching", "tv_calendar"):
                result = await session.call_tool(name, {})
                payload = result.structuredContent
                print(name, "payload_keys=", sorted(payload.keys()) if isinstance(payload, dict) else None,
                      "is_error=", result.isError)
                print(name, "error=", payload.get("error") if isinstance(payload, dict) else None)
                print(name, "http_status=", payload.get("http_status") if isinstance(payload, dict) else None,
                      "items_present=", isinstance(payload, dict) and isinstance(payload.get("items"), list))


if __name__ == "__main__":
    asyncio.run(main())
