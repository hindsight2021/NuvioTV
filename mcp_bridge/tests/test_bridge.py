"""Socket-level checks for the TV REST adapter; no real TV commands."""

import asyncio
from contextlib import asynccontextmanager
import json
import os
import sys
import threading
import unittest
from unittest.mock import AsyncMock, patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit

os.environ.setdefault("NUVIO_API_URL", "http://127.0.0.1:1")
os.environ.setdefault("NUVIO_API_TOKEN", "test-token")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server  # noqa: E402
import httpx  # noqa: E402
from title_resolver import resolve_target  # noqa: E402


class Handler(BaseHTTPRequestHandler):
    calls = []

    def log_message(self, *_args):
        pass

    def _respond(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        parsed = urlsplit(self.path)
        self.calls.append({
            "method": self.command,
            "path": parsed.path,
            "params": parse_qs(parsed.query),
            "body": json.loads(body) if body else None,
            "auth": self.headers.get("Authorization"),
        })
        if parsed.path == "/api/v1/queue":
            payload = json.dumps({"error": "token=" + self.headers.get("Authorization", "")}).encode()
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        if parsed.path == "/api/v1/play":
            result = {"status": "ok", "message": "Playback request accepted",
                      "data": {"id": "123e4567-e89b-12d3-a456-426614174000",
                               "status": "RESOLVING"}}
        elif parsed.path.endswith("/requests/123e4567-e89b-12d3-a456-426614174000"):
            result = {"id": "123e4567-e89b-12d3-a456-426614174000", "status": "STARTED"}
        else:
            result = {"status": "ok"}
        payload = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    do_GET = _respond
    do_POST = _respond


class BridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        server.BASE_URL = f"http://127.0.0.1:{cls.http.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.thread.join()
        cls.http.server_close()

    def setUp(self):
        Handler.calls.clear()

    def run_async(self, coro):
        return asyncio.run(coro)

    def test_exact_episode_payload_and_accepted_state(self):
        result = self.run_async(server.play_exact("98765", "series", 2, 4))
        self.assertTrue(result["request_accepted"])
        self.assertFalse(result["playback_started"])
        self.assertEqual(result["request"]["status"], "RESOLVING")
        call = Handler.calls[0]
        self.assertEqual(call["path"], "/api/v1/play")
        self.assertEqual(call["body"], {
            "contentId": "98765", "type": "series", "season": 2, "episode": 4
        })
        self.assertEqual(call["params"]["season"], ["2"])
        self.assertEqual(call["params"]["episode"], ["4"])
        self.assertEqual(call["auth"], "Bearer test-token")
        self.assertNotIn("test-token", json.dumps(result))

    def test_calendar_passes_bounded_period_without_tv_navigation(self):
        result = self.run_async(server.tv_calendar("upcoming", 5))
        self.assertEqual(result["status"], "ok")
        self.assertEqual(Handler.calls, [{
            "method": "GET", "path": "/api/v1/calendar/tv",
            "params": {"period": ["upcoming"], "days": ["5"]},
            "body": None, "auth": "Bearer test-token",
        }])

    def test_resume_uses_saved_episode_and_never_claims_started(self):
        saved = {"items": [{"title": "Below Deck Sailing Yacht", "contentId": "tt1234567",
                             "contentType": "series", "season": 3, "episode": 4,
                             "positionMs": 851000}]}
        with patch.object(server, "continue_watching", new=AsyncMock(return_value=saved)):
            result = self.run_async(server.resume_continue_watching("below deck sailing yacht"))
        self.assertTrue(result["request_accepted"])
        self.assertFalse(result["playback_started"])
        self.assertEqual(result["saved_item"]["positionMs"], 851000)
        self.assertEqual(Handler.calls[0]["body"], {
            "contentId": "tt1234567", "type": "series", "season": 3, "episode": 4,
        })

    def test_ambiguous_resume_does_not_contact_tv(self):
        saved = {"items": [{"title": "Example", "contentId": "tt1", "contentType": "series",
                             "season": 1, "episode": 1},
                            {"title": "Example", "contentId": "tt2", "contentType": "series",
                             "season": 2, "episode": 3}]}
        with patch.object(server, "continue_watching", new=AsyncMock(return_value=saved)):
            result = self.run_async(server.resume_continue_watching("Example"))
        self.assertFalse(result["playback_requested"])
        self.assertEqual(Handler.calls, [])

    def test_unloaded_progress_does_not_play(self):
        with patch.object(server, "continue_watching", new=AsyncMock(return_value={
            "progressLoaded": False, "items": [],
        })):
            result = self.run_async(server.resume_continue_watching("Example"))
        self.assertFalse(result["playback_requested"])
        self.assertIn("not finished loading", result["error"])
        self.assertEqual(Handler.calls, [])

    def test_request_status_reports_tv_state(self):
        result = self.run_async(
            server.play_request_status("123e4567-e89b-12d3-a456-426614174000"))
        self.assertEqual(result["status"], "STARTED")
        self.assertEqual(Handler.calls[0]["method"], "GET")

    def test_invalid_coordinates_never_contact_tv(self):
        with self.assertRaises(ValueError):
            self.run_async(server.play_exact("98765", "series", 2, None))
        with self.assertRaises(ValueError):
            self.run_async(server.play_exact("98765", "series", 1, 0))
        with self.assertRaises(ValueError):
            self.run_async(server.play_exact("98765", "movie", 1, 1))
        self.assertEqual(Handler.calls, [])

    def test_search_is_navigation_not_catalog_result(self):
        result = self.run_async(server.search_on_tv("Dune"))
        self.assertEqual(result["status"], "ok")
        self.assertEqual(Handler.calls[0]["path"], "/api/v1/navigation/search")
        self.assertEqual(Handler.calls[0]["body"], {"query": "Dune"})

    def test_upstream_auth_error_does_not_echo_token(self):
        result = self.run_async(server.queue())
        self.assertFalse(result["ok"])
        self.assertEqual(result["http_status"], 401)
        self.assertNotIn("test-token", json.dumps(result))

    def test_one_shot_title_play_sends_exact_selected_episode(self):
        target = {"content_id": "tt0088526", "content_type": "series",
                  "title": "The Golden Girls", "season": 2, "episode": 4,
                  "episode_title": "It's a Miserable Life", "selection": "exact"}
        with patch.object(server, "resolve_target", new=AsyncMock(return_value=target)):
            result = self.run_async(server.play_show_episode("The Golden Girls", "exact", 2, 4))
        self.assertEqual(result["selected"]["episode_title"], "It's a Miserable Life")
        self.assertTrue(result["request_accepted"])
        self.assertFalse(result["playback_started"])
        self.assertEqual(Handler.calls[0]["body"], {
            "contentId": "tt0088526", "type": "series", "season": 2, "episode": 4})

    def test_bad_selection_never_contacts_tv(self):
        result = self.run_async(server.play_show_episode("The Golden Girls", "exact", 2, None))
        self.assertFalse(result["playback_requested"])
        self.assertEqual(Handler.calls, [])

    def test_exact_episode_can_use_current_series_without_title(self):
        target = {"content_id": "tt0088526", "content_type": "series",
                  "title": "The Golden Girls", "season": 2, "episode": 4}
        current = {"playback": {"contentType": "series", "contentName": "The Golden Girls"}}
        with patch.object(server, "status", new=AsyncMock(return_value=current)), \
                patch.object(server, "resolve_target", new=AsyncMock(return_value=target)) as resolve:
            result = self.run_async(server.play_show_episode(selection="exact", season=2, episode=4))
        self.assertTrue(result["request_accepted"])
        self.assertEqual(resolve.await_args.args[3], "The Golden Girls")

    def test_no_current_series_returns_without_tv_write(self):
        with patch.object(server, "status", new=AsyncMock(return_value={"playback": None})):
            result = self.run_async(server.play_show_episode(selection="exact", season=2, episode=4))
        self.assertFalse(result["playback_requested"])
        self.assertEqual(Handler.calls, [])

    def test_set_light_color_simulated_defaults(self):
        with patch.object(server, "HA_TOKEN", ""):
            result = self.run_async(server.set_light_color())
        self.assertFalse(result["ok"])
        self.assertIn("credentials", result["error"])

    def test_set_light_color_simulated_custom_args(self):
        with patch.object(server, "HA_TOKEN", ""):
            result = self.run_async(server.set_light_color(
                brightness_pct=10, transition_seconds=5.0, color="#ff0000", entity_id="light.living_room"))
        self.assertFalse(result["ok"])
        self.assertIn("credentials", result["error"])

    def test_set_light_color_live_turn_off_on_zero_brightness(self):
        captured_requests = []

        def reply(request):
            captured_requests.append(request)
            return httpx.Response(200, json={"status": "ok"})

        mock_transport = httpx.MockTransport(reply)
        with patch.object(server, "HA_TOKEN", "mock-ha-token"), \
             patch.object(httpx, "AsyncClient", return_value=httpx.AsyncClient(transport=mock_transport)):
            result = self.run_async(server.set_light_color(brightness_pct=0, transition_seconds=3.0))

        self.assertTrue(result["ok"])
        self.assertEqual(result["service"], "turn_off")
        self.assertEqual(result["service_data"], {"entity_id": "light.family_room", "transition": 3.0})
        self.assertEqual(len(captured_requests), 1)
        self.assertTrue(captured_requests[0].url.path.endswith("/services/light/turn_off"))

    def test_set_light_color_live_hex_color_conversion(self):
        captured_requests = []

        def reply(request):
            captured_requests.append(request)
            return httpx.Response(200, json={"status": "ok"})

        mock_transport = httpx.MockTransport(reply)
        with patch.object(server, "HA_TOKEN", "mock-ha-token"), \
             patch.object(httpx, "AsyncClient", return_value=httpx.AsyncClient(transport=mock_transport)):
            result = self.run_async(server.set_light_color(brightness_pct=40, transition_seconds=6.0, color="#00ff00"))

        self.assertTrue(result["ok"])
        self.assertEqual(result["service"], "turn_on")
        self.assertEqual(result["service_data"]["rgb_color"], [0, 255, 0])
        self.assertEqual(result["service_data"]["brightness_pct"], 40)
        self.assertEqual(result["service_data"]["transition"], 6.0)

    def test_set_light_color_live_named_color(self):
        captured_requests = []

        def reply(request):
            captured_requests.append(request)
            return httpx.Response(200, json={"status": "ok"})

        mock_transport = httpx.MockTransport(reply)
        with patch.object(server, "HA_TOKEN", "mock-ha-token"), \
             patch.object(httpx, "AsyncClient", return_value=httpx.AsyncClient(transport=mock_transport)):
            result = self.run_async(server.set_light_color(brightness_pct=30, transition_seconds=5.0, color="warm white"))

        self.assertTrue(result["ok"])
        self.assertEqual(result["service"], "turn_on")
        self.assertEqual(result["service_data"]["color_name"], "warm white")

    def test_set_light_color_uses_authenticated_ha_mcp(self):
        calls = []

        @asynccontextmanager
        async def fake_transport(_url, http_client):
            self.assertEqual(http_client.headers["Authorization"], "Bearer test-ha")
            yield (None, None, None)

        class FakeSession:
            def __init__(self, *_streams):
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, *_args):
                pass

            async def initialize(self):
                pass

            async def call_tool(self, name, args):
                calls.append((name, args))
                return SimpleNamespace(structuredContent={"success": True},
                                       isError=False, content=[])

        with patch.object(server, "HA_MCP_URL", "http://ha/mcp"), \
             patch.object(server, "HA_MCP_AUTHORIZATION", "Bearer test-ha"), \
             patch.object(server, "streamable_http_client", fake_transport), \
             patch.object(server, "ClientSession", FakeSession):
            result = self.run_async(server.set_light_color(40, 3.0, "#ff0000"))

        self.assertTrue(result["ok"])
        self.assertEqual(calls, [("ha_call_service", {
            "domain": "light", "service": "turn_on", "entity_id": "light.family_room",
            "data": {"brightness_pct": 40, "transition": 3.0, "rgb_color": [255, 0, 0]},
        })])

    def test_set_light_rejects_non_light_target(self):
        result = self.run_async(server.set_light_color(entity_id="switch.kitchen"))
        self.assertFalse(result["ok"])
        self.assertIn("light", result["error"])

    def test_set_light_clamping(self):
        with patch.object(server, "HA_TOKEN", ""):
            res_min = self.run_async(server.set_light(brightness_pct=-20, transition_seconds=-5.0))
            self.assertFalse(res_min["ok"])

            res_max = self.run_async(server.set_light(brightness_pct=250, transition_seconds=50.0))
            self.assertFalse(res_max["ok"])


class ResolverTests(unittest.TestCase):
    def run_async(self, coro):
        return asyncio.run(coro)

    def client(self):
        def reply(request):
            if request.url.path == "/api/nuvio/search":
                return httpx.Response(200, json=[
                    {"id": "tt0088526", "title": "The Golden Girls", "type": "series", "year": "1985-1992"},
                    {"id": "tt2359096", "title": "Golden Girls", "type": "series", "year": "2012"}])
            if request.url.path == "/meta/series/tt0088526.json":
                return httpx.Response(200, json={"meta": {"id": "tt0088526", "videos": [
                    {"season": 0, "episode": 1, "name": "Special"},
                    {"season": 1, "episode": 1, "name": "Pilot", "released": "1985-09-14T00:00:00Z"},
                    {"season": 2, "episode": 4, "name": "It's a Miserable Life", "released": "1986-10-18T00:00:00Z"},
                    {"season": 9, "episode": 1, "name": "Future", "released": "2099-01-01T00:00:00Z"}
                ]}})
            return httpx.Response(404)
        return httpx.AsyncClient(transport=httpx.MockTransport(reply))

    def resolve(self, selection="first", season=None, episode=None, year=1985):
        async def work():
            async with self.client() as client:
                return await resolve_target(client, "http://metadata", "https://cinemeta",
                                            "The Golden Girls", "series", selection,
                                            season, episode, year)
        return self.run_async(work())

    def test_first_and_random_exclude_specials_and_future(self):
        self.assertEqual((self.resolve()["season"], self.resolve()["episode"]), (1, 1))
        for _ in range(25):
            item = self.resolve("random")
            self.assertIn((item["season"], item["episode"]), {(1, 1), (2, 4)})

    def test_exact_never_substitutes_episode(self):
        self.assertEqual(self.resolve("exact", 2, 4)["episode_title"], "It's a Miserable Life")
        with self.assertRaisesRegex(ValueError, "S2E3"):
            self.resolve("exact", 2, 3)
        self.assertEqual(self.resolve("exact", 0, 1)["episode_title"], "Special")

    def test_year_filter_fails_without_playing_another_version(self):
        with self.assertRaisesRegex(ValueError, "No confident title match"):
            self.resolve("first", year=2005)


if __name__ == "__main__":
    unittest.main()
