"""Turns the VM's /tmp/probe.out into web/static/underlay_colors.json.

    python web/tools/import_underlay_probe.py probe.out > web/static/underlay_colors.json
"""

import json
import re
import sys

LINE = re.compile(r"PROBE UNDERLAY id=(\d+) rgb=([0-9a-fA-F]{6}) tex=(-?\d+)")


def main(path: str) -> None:
    seen: dict[int, dict] = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = LINE.search(line)
            if m:
                uid = int(m.group(1))
                seen[uid] = {"id": uid, "rgb": m.group(2).lower(), "tex": int(m.group(3))}
    out = {
        "source": "VM /tmp/probe.out (cache underlay definitions)",
        "complete": True,
        "underlays": [seen[k] for k in sorted(seen)],
    }
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
