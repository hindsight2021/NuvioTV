# Nuvio+ private MCP bridge

This bridge exposes bounded Nuvio+ REST tools over MCP Streamable HTTP. It
listens on `127.0.0.1:8765/mcp`, uses the TV's bearer token only on the Pi,
and never exposes raw ADB or arbitrary REST calls.

The TV API currently accepts POST fields through query parameters. This bridge
sends the same fields in JSON and in the query string for compatibility with
the installed build. Credentials are never placed in the URL.

## Raspberry Pi deployment

Create a Python 3.11 virtual environment, install `requirements.txt`, and
start `server.py` with `NUVIO_API_URL` and `NUVIO_API_TOKEN` in its
environment. Both values already exist in the private Alexa bridge environment
file on this Pi; the MCP systemd unit can reuse that file without copying or
printing the token.

On CodexPi the systemd service is `nuvio-mcp-bridge.service`. It is enabled
for startup. The local endpoint is `http://127.0.0.1:8765/mcp`. Tailscale
Serve provides a persistent, **tailnet-only** HTTPS endpoint at
`https://codexpi.tailefa84.ts.net:8443/mcp`. The existing public Funnel on
port 443 is separate. ChatGPT cannot access the private URL; connect ChatGPT
with an OpenAI Secure MCP Tunnel pointing at the local endpoint. Do not put
this unauthenticated MCP listener behind Tailscale Funnel.

## ChatGPT configuration

The official `tunnel-client` v0.0.16 Linux arm64 binary is installed at
`/usr/local/bin/tunnel-client` on CodexPi; its release SHA-256 matched
`SHA256SUMS.txt`. The OpenAI Platform tunnel is `Nuvio+ Home`, ID
`tunnel_6ac7b213a81c8191aabd617fe0dd3aa7`. Its runtime key is stored
only in root-owned `/etc/nuvio-mcp-tunnel.env` on the Pi. The key has Tunnels
Read and Use permissions; do not commit or print it. The enabled
`nuvio-mcp-tunnel.service` uses profile
`/opt/nuvio-mcp-bridge/tunnel-profiles/nuvio-home.yaml` to connect outbound
to OpenAI and forward to `http://127.0.0.1:8765/mcp`. Check the service with
`systemctl status nuvio-mcp-tunnel.service` and its local readiness endpoint
at `http://127.0.0.1:8766/readyz`. In ChatGPT web, choose Plugins → Add
custom MCP server → Tunnel, enter the tunnel ID, choose No authentication
for the private local MCP (the tunnel controls access), create and install
the plugin. The installed ChatGPT plugin is [Nuvio+ Home](https://chatgpt.com/plugins/plugin_asdk_app_6ac7b455e1e48191ac656e8a2190b375).
To use it in a new ChatGPT conversation, mention `@Nuvio+ Home` and state
the requested action. See the
official [Secure MCP Tunnel guide](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels).

`search_catalog` returns catalog matches without changing the TV screen.
`play_show_episode` resolves a title and sends an exact season/episode request
to the TV in one call. Use `selection="first"` for “play an episode,”
`selection="random"` for a uniformly chosen aired regular episode, or
`selection="exact"` with season and episode for SxEy. A title can be omitted
only when the current player or queue identifies a series. Automatic picks
exclude Season 0 specials; an explicit S0 episode remains available. A year
can distinguish same-name shows. For identical title matches without a year,
the local catalog's first-ranked result is used and returned in `selected`.
`play_movie_by_title` handles movies. The original `play_exact` still accepts
a verified content ID. `search_on_tv` remains a navigation command.

`continue_watching` reads the TV's saved titles, episode coordinates, and
positions, plus last-watched seeds for shows between episodes. The seeds are
context, not a resolved next episode. `resume_continue_watching(title)` matches exactly one saved title
and requests that same episode through Nuvio's normal resume path. It reports
request acceptance separately from actual playback; check
`play_request_status`. `tv_calendar(period, days)` reads the Simkl TV schedule
for the current day and up to seven days before or after it. Dates use the
TV's local timezone; an item listed for today is not guaranteed to have aired
yet. These tools require a TV APK with `/api/v1/continue-watching` and
`/api/v1/calendar/tv`; an older installed APK returns HTTP 404.

Title lookup uses the local metadata service at `NUVIO_METADATA_URL` (currently
`http://192.168.1.4:8765`); episode lists use the official Cinemeta add-on
over HTTPS. If metadata cannot identify an aired episode, the bridge requests
no playback. These lists describe episode identity, not stream availability;
the TV may still fail to find a playable source. A play request marked
`RESOLVING` is not playing. Check `play_request_status` for `STARTED`, `FAILED`
or `CANCELLED` before reporting the outcome.

## Verified on 2026-10-08

- MCP initialize, tools/list (11 tools), and `status` succeeded through the
  private HTTPS address; the TV returned Nuvio+ `0.9.4-plus.51`.
- With no active playback, `search_on_tv("Nuvio")` succeeded over MCP, then
  `dpad("back")` succeeded.
- Local adapter tests verify exact season/episode coordinates, request
  acceptance vs playback start, invalid coordinate rejection, and TV search
  request shape. Playback itself was not started for testing.
- `tunnel-client doctor --profile nuvio-home --profile-dir
  /opt/nuvio-mcp-bridge/tunnel-profiles --explain` passed, the tunnel service
  started, and `/readyz` returned HTTP 200.
- The ChatGPT plugin was created and connected. A read-only `@Nuvio+ Home`
  status request returned live TV version `0.9.4-plus.51`, the main screen,
  and an empty queue through ChatGPT. No playback or navigation changed.

## Direct-play update, 2026-10-08

- The bridge now exposes 18 tools, including title search, one-shot movie and
  first/random/exact episode play, relative seek, speed, screen opening and
  queue clearing. The Pi bridge and private HTTPS endpoint list all 18.
- Live read-only resolution for *The Golden Girls* returned S1E1 for `first`,
  S2E4 for `exact`, and an aired episode for `random`. It did not start playback.
- Twelve local tests passed. They check exact coordinates, Season 0 exclusion
  for automatic picks, future episode exclusion, and no TV write on invalid
  or unmatched requests.

## Home Assistant Lighting Controls

- `set_light_color(brightness_pct, transition_seconds, color, entity_id)`:
  Controls light dimming (0–100%), fade duration (0–10.0 seconds), and optional
  color (hex like `#ff9900` or CSS/HA color name like `warm white`).
  - `brightness_pct`: 0 turns the light off via `light.turn_off`, >0 adjusts brightness via `light.turn_on`.
  - `transition_seconds`: effect duration clamped between 0.0 and 10.0 seconds (default 7.0s for cinema-style slow fade).
  - `entity_id`: targets the family room lights (`light.family_room`) by default, matching cinema mode setup.
  - Calls the existing Home Assistant MCP `ha_call_service` when `HA_MCP_URL` and `HA_MCP_AUTHORIZATION` are configured. An optional `HA_TOKEN` supports direct Home Assistant REST as a fallback. Missing credentials return an error, never a simulated success. Targets must be `light.*` entities.
- `set_light(...)`: alias for `set_light_color`.

## Home Assistant Network Control switches

Three `input_boolean` helpers on the Network Ops Center dashboard control
Pi MCP service groups. The independent `mcp-service-guard.service` reads them
through the existing private Home Assistant MCP every 15 seconds and only
allows the fixed unit names in `mcp_service_guard.py`. Turning a helper off
stops and disables its service and tunnel; the disabled state persists after
a Pi restart. Unknown or unreachable Home Assistant state leaves the last
service state unchanged. The Home Assistant helper indicates desired state,
not an independent systemd status sensor. The Home Assistant MCP switch stops
the Pi tunnel; its upstream backend on Home Assistant stays running.

## Separate Home Assistant ChatGPT connection

The Pi also runs `nuvio-ha-mcp-tunnel.service`, connecting the existing private
Home Assistant MCP to OpenAI Secure MCP Tunnel
`tunnel_6ac8297f40248191bb3e587806cdb8cd`. Its ChatGPT app is
**Home Assistant Home**. The tunnel injects the upstream authorization header
from `/etc/nuvio-ha-mcp.env` and keeps that credential off ChatGPT and out of
the plugin configuration. The tunnel profile is mode 640 and the service runs
as `pi`. The OpenAI control-plane key stays in `/etc/nuvio-mcp-tunnel.env`.

The upstream registered 77 MCP tools, including `ha_call_service`,
`ha_get_state`, `ha_search`, and `ha_get_overview`. To enumerate every sensor,
call `ha_get_overview` with `domains=["sensor"]`, `detail_level="full"`, a
bounded `limit` (for example 200), and advance `offset` to `next_offset` until
`pagination.has_more` is false. The Home Assistant MCP reported 1,349 sensor
entities at setup time; this count changes as entities change.

Read-only transport smoke test on the Pi:

```sh
cd /opt/nuvio-mcp-bridge
.venv/bin/python smoke_ha_mcp.py
curl -fsS http://127.0.0.1:8768/readyz
```
