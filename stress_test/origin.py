"""Mock origin API for load-testing CacheFlow.

GET  /item/<id>   -> JSON after a fixed delay (simulates a slow upstream)
GET  /__stats     -> how many /item requests actually reached the origin
POST /__reset     -> zero the counter

Run:  python origin.py            (port 9000, 150 ms delay)
      python origin.py 9000 150   (port, delay in ms)
Query /__stats on the ORIGIN port directly, never through the proxy.
"""
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9000
DELAY_MS = int(sys.argv[2]) if len(sys.argv) > 2 else 150

lock = threading.Lock()
hits = 0


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # keep-alive, so clients reuse connections

    def _send(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        global hits
        if self.path == "/__stats":
            with lock:
                return self._send(200, {"origin_hits": hits})
        if self.path.startswith("/item/"):
            time.sleep(DELAY_MS / 1000)
            with lock:
                hits += 1
            return self._send(200, {"id": self.path.split("/")[-1], "name": "demo item"})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        global hits
        if self.path == "/__reset":
            with lock:
                hits = 0
            return self._send(200, {"origin_hits": 0})
        self._send(404, {"error": "not found"})

    def log_message(self, *args):
        pass  # keep the console quiet under load


class Server(ThreadingHTTPServer):
    request_queue_size = 512  # default is 5, which refuses bursts of connections
    daemon_threads = True


if __name__ == "__main__":
    print(f"origin on :{PORT}, delay {DELAY_MS} ms")
    Server(("0.0.0.0", PORT), Handler).serve_forever()