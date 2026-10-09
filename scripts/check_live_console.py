#!/usr/bin/env python3
"""Check that the live consoles were deployed with their placeholders filled.

`check_console.py` holds the committed files to the opposite rule: there,
`__API_BASE_URL__` and `__API_ORIGIN__` must stay placeholders. Only
`deploy-console.sh` swaps in the gateway for the length of a deploy. Any other
Hosting deploy (a bare `firebase deploy`, say for one `assetlinks.json` line)
ships the placeholders as they are. The pages still load, and every API call
fails: on 2026-10-07 that went unnoticed until the next sign-in.

So this reads what the live site serves, and fails when:

1. `/console/config.js` still says `__API_BASE_URL__`, or names a gateway
   other than the one given;
2. the Content-Security-Policy on `/login`, `/account` or `/console/` still
   says `__API_ORIGIN__`, or does not admit that gateway in `connect-src`.

`deploy-console.sh` runs it after every deploy. Run it on its own any time:

    python scripts/check_live_console.py --api https://<gateway-host>

`--origin` defaults to https://app.sempermechanics.com. Without `--api` it
checks only that no placeholder is left. Exit 1 on failure, after reporting
everything it found. The CDN can serve the old release for a few seconds after
a deploy, so a failing read is retried before it counts.
"""
from __future__ import annotations

import argparse
import http.client
import re
import sys
import time
import urllib.request

PAGES_WITH_CONSOLE_CSP = ("/login", "/account", "/console/")
PLACEHOLDERS = ("__API_BASE_URL__", "__API_ORIGIN__")


def fetch(url: str) -> tuple[str, str]:
    """The body and Content-Security-Policy header of `url`, uncached."""
    req = urllib.request.Request(url, headers={"Cache-Control": "no-cache"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.read().decode("utf-8", "replace"), resp.headers.get("Content-Security-Policy", "")


# What a read can fail with: URLError and timeouts are OSErrors, and so is a
# connection reset while the body streams; a body cut short is IncompleteRead.
_UNREADABLE = (OSError, http.client.HTTPException)


def problems(origin: str, api: str | None) -> list[str]:
    found: list[str] = []
    try:
        config, _ = fetch(f"{origin}/console/config.js")
    except _UNREADABLE as e:
        return [f"/console/config.js: could not read it ({e})"]
    m = re.search(r'API_BASE_URL\s*=\s*"([^"]*)"', config)
    served = m.group(1) if m else None
    if served is None:
        found.append("/console/config.js: no API_BASE_URL in it")
    elif served in PLACEHOLDERS:
        found.append(f"/console/config.js: API_BASE_URL is still {served}")
    elif api and served != api:
        found.append(f"/console/config.js: API_BASE_URL is {served}, expected {api}")

    for path in PAGES_WITH_CONSOLE_CSP:
        try:
            _, csp = fetch(f"{origin}{path}")
        except _UNREADABLE as e:
            found.append(f"{path}: could not read it ({e})")
            continue
        if not csp:
            found.append(f"{path}: no Content-Security-Policy header")
            continue
        left = [p for p in PLACEHOLDERS if p in csp]
        if left:
            found.append(f"{path}: CSP still says {', '.join(left)}")
        connect = next((d for d in csp.split(";") if d.strip().startswith("connect-src")), "")
        if api and api not in connect.split():
            found.append(f"{path}: CSP connect-src does not admit {api}")
    return found


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--origin", default="https://app.sempermechanics.com",
                    help="the Hosting origin to read (no trailing slash)")
    ap.add_argument("--api", help="the gateway origin the deploy substituted (no trailing slash)")
    ap.add_argument("--attempts", type=int, default=6)
    ap.add_argument("--wait", type=float, default=5.0, help="seconds between attempts")
    args = ap.parse_args()
    origin = args.origin.rstrip("/")
    api = args.api.rstrip("/") if args.api else None

    found: list[str] = []
    for attempt in range(1, args.attempts + 1):
        found = problems(origin, api)
        if not found:
            print(f"Live console at {origin} has its gateway filled in"
                  + (f" ({api})." if api else "."))
            return 0
        if attempt < args.attempts:
            time.sleep(args.wait)

    print(f"Live console at {origin} is not deployed correctly:", file=sys.stderr)
    for p in found:
        print(f"  - {p}", file=sys.stderr)
    print("Redeploy with scripts/deploy-console.sh (see CLAUDE.md).", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
