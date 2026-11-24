#!/usr/bin/env python3
"""Static server for the client webconfig (osrs-webconfig.service), localhost only.

Same as `python3 -m http.server`, plus `Cache-Control: no-cache` so RuneLite/Microbot revalidate
jav_config.ws, world_list.ws and runelite/* on every launch. Without it their OkHttp disk cache
can reuse a stale copy for days (heuristic freshness from Last-Modified).

Usage: serve.py <port> <directory>    (exposed on :443 by `tailscale serve`)
"""

import functools
import http.server
import sys


class NoCacheHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-cache")
        super().end_headers()


if __name__ == "__main__":
    port, directory = int(sys.argv[1]), sys.argv[2]
    handler = functools.partial(NoCacheHandler, directory=directory)
    http.server.ThreadingHTTPServer(("127.0.0.1", port), handler).serve_forever()
