"""Wait for the local Nuvio MCP listener before starting its tunnel."""

import socket
import time

for attempt in range(30):
    try:
        with socket.create_connection(("127.0.0.1", 8765), timeout=1):
            break
    except OSError:
        time.sleep(1)
else:
    raise SystemExit("Nuvio MCP listener did not start within 30 seconds")
