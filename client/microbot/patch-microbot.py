#!/usr/bin/env python3
"""Patch a Microbot release jar so it logs in to the BeanLab server directly.

Usage:
    python3 patch-microbot.py microbot-2.6.65.jar microbot-2.6.65-beanlab.jar <server modulus hex> [server base URL]

The modulus is the public half of the server's RSA login key (`.data/game.key` on the
VM), also the `modulus:` value of the BeanLab target in ~/.rsprox/proxy-targets.yaml.
It is not secret. The server base URL defaults to https://runescape-server.tail5475a4.ts.net.

The script is version-agnostic: it locates each target by content, not by class name,
so a new Microbot release does not need a code change here — only a new modulus file
if the server key ever rotates.

Patches applied (exactly these, every other jar entry copied byte for byte):
  1. The RSA login modulus constant (Jagex's 1024-bit public modulus) -> the server's.
     Historically in `bc.class` (2.0.x); the script finds whichever class carries it.
  2. The host-allowlist entry "127.0.0.1" -> "" in the class that performs the
     getHost()/endsWith host check (RSProx makes the same change; endsWith("") accepts
     any host). Historically `bl.class` (2.0.x), moved to another class in 2.6.x —
     the script finds it by scanning for the string + a getHost reference.
  3. MicrobotVersionChecker's "https://microbot.cloud/api/version/client" ->
     <base>/runelite/client/<build>.txt, where <build> is CLIENT_BUILD next to this
     script. The server answers 2.0.20 (up to date) for the current build and 2.0.21
     for older ones, so an out-of-date BeanLab client shows Microbot's own
     "(NEW CLIENT AVAILABLE)" prompt. (Newer real Microbot releases can't log in to
     the server's rev, so this check tracks BeanLab client builds, not Microbot's.)

The game port stays 43594; the server address comes from --jav_config at launch.
"""

import hashlib
import pathlib
import re
import struct
import sys
import zipfile

VERSION_CHECKER = "net/runelite/client/plugins/microbot/MicrobotVersionChecker.class"
MICROBOT_VERSION_URL = "https://microbot.cloud/api/version/client"
DEFAULT_SERVER_BASE = "https://runescape-server.tail5475a4.ts.net"

# Jagex's RSA login modulus (256 hex chars, 1024-bit). Any 1024-bit modulus in a
# release jar is assumed to be this one.
JAGEX_MODULUS = (
    "8b3a6290fcfe8f8d0752bfd815158328bf43b89e91356380968f51465f29f689dde6c169375e12dd28a43633b6"
    "19a48f71c539c9f1f4e59aadd5e2ad681f3e1270313ef044889faee740f641ea0f8b341d44f388758e12b893f4"
    "6fb7515ed8dc9b6ded076f1a621cf0d6f2157a32434e5f3028e3baf3ff2f08ab86de8776638f"
)

# Constant-pool entry sizes (excluding the tag byte) for non-UTF8 tags.
_CP_SIZES = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 16: 2, 19: 2, 20: 2, 9: 4, 10: 4, 11: 4, 12: 4,
             17: 4, 18: 4, 15: 3}


def cp_utf8(data: bytes) -> list:
    """Decode a class file's constant pool; return the UTF8 entries in index order."""
    out = []
    count = struct.unpack(">H", data[8:10])[0]
    i, k = 10, 1
    while k < count:
        tag = data[i]
        if tag == 1:
            length = struct.unpack(">H", data[i + 1:i + 3])[0]
            out.append(data[i + 3:i + 3 + length].decode("latin1"))
            i += 3 + length
        else:
            i += 1 + _CP_SIZES[tag]
            if tag in (5, 6):  # long/double take two slots
                k += 1
        k += 1
    return out


def rewrite_utf8_constants(data: bytes, replacements: dict) -> bytes:
    """Replace whole CONSTANT_Utf8 entries; each key must occur exactly once."""
    out = bytearray(data[:10])
    count = struct.unpack(">H", data[8:10])[0]
    i, k = 10, 1
    hits = dict.fromkeys(replacements, 0)
    while k < count:
        tag = data[i]
        if tag == 1:
            length = struct.unpack(">H", data[i + 1:i + 3])[0]
            value = data[i + 3:i + 3 + length]
            if value in replacements:
                new = replacements[value]
                out += bytes([1]) + struct.pack(">H", len(new)) + new
                hits[value] += 1
            else:
                out += data[i:i + 3 + length]
            i += 3 + length
        else:
            size = 1 + _CP_SIZES[tag]
            out += data[i:i + size]
            i += size
            if tag in (5, 6):
                k += 1
        k += 1
    bad = {key.decode("latin1")[:20]: n for key, n in hits.items() if n != 1}
    if bad:
        raise SystemExit(f"constant not found exactly once (wrong jar?): {bad}")
    return bytes(out + data[i:])


def main() -> None:
    if len(sys.argv) not in (4, 5):
        raise SystemExit(__doc__)
    src, dst = sys.argv[1], sys.argv[2]
    modulus = sys.argv[3].strip().lower()
    base = (sys.argv[4] if len(sys.argv) == 5 else DEFAULT_SERVER_BASE).rstrip("/")
    if len(modulus) != len(JAGEX_MODULUS) or any(c not in "0123456789abcdef" for c in modulus):
        raise SystemExit(f"modulus must be {len(JAGEX_MODULUS)} hex chars (1024-bit key)")
    if not base.startswith("https://"):
        raise SystemExit("server base URL must start with https://")

    build_file = pathlib.Path(__file__).with_name("CLIENT_BUILD")
    build = int(build_file.read_text().strip()) if build_file.exists() else 1
    version_url = f"{base}/runelite/client/{build}.txt"

    # Locate targets by content, so a renamed class in a new release does not break the patch.
    modulus_target = modulus_hex = None
    host_target = None
    with zipfile.ZipFile(src) as z:
        names = z.namelist()
        for n in names:
            if not n.endswith(".class"):
                continue
            data = z.read(n)
            # The login modulus class builds an RSA public key: it references BigInteger and the
            # public exponent 10001 (65537), and carries one 1024-bit hex string.
            if b"BigInteger" in data and b"10001" in data:
                hexes = re.findall(rb"[0-9a-fA-F]{256}", data)
                if len(hexes) == 1:
                    if modulus_target is not None and modulus_target != n:
                        raise SystemExit(f"modulus found in multiple classes: {modulus_target} and {n}")
                    modulus_target, modulus_hex = n, hexes[0]
            if b"127.0.0.1" in data and b"getHost" in data and b"endsWith" in data:
                host_target = n  # expected unique; keep the last match

        if modulus_target is None or modulus_hex is None:
            raise SystemExit("no class looks like the RSA login-modulus class (wrong jar?)")
        if host_target is None:
            raise SystemExit('no class contains "127.0.0.1" + getHost + endsWith (wrong jar?)')
        if VERSION_CHECKER not in names:
            raise SystemExit(f"{VERSION_CHECKER} not found (wrong jar?)")
        print(f"modulus in  : {modulus_target}  (value {modulus_hex.decode()[:16]}…)")
        print(f"host check  : {host_target}")
        print(f"version chk : {VERSION_CHECKER}")
        print(f"client build {build}: version check -> {version_url}")

        patches = {
            modulus_target: {modulus_hex: modulus.encode()},
            host_target: {b"127.0.0.1": b""},
            VERSION_CHECKER: {MICROBOT_VERSION_URL.encode(): version_url.encode()},
        }
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
            for info in z.infolist():
                data = z.read(info.filename)
                if info.filename in patches:
                    data = rewrite_utf8_constants(data, patches[info.filename])
                    print("patched", info.filename)
                zout.writestr(info, data, compress_type=info.compress_type)
    print("wrote", dst)

    # Verify the result: the original modulus value is gone, no 127.0.0.1 host check left,
    # version URL replaced, and the jar still lists every entry.
    with zipfile.ZipFile(dst) as z:
        bad = z.testzip()
        if bad is not None:
            raise SystemExit(f"corrupt entry in output jar: {bad}")
        leftovers = []
        for n in z.namelist():
            if not n.endswith(".class"):
                continue
            data = z.read(n)
            if n == modulus_target and modulus_hex in data:
                leftovers.append(f"original modulus still in {n}")
            if n == host_target and b"127.0.0.1" in data:
                leftovers.append(f"127.0.0.1 still in {n}")
            if n == VERSION_CHECKER and MICROBOT_VERSION_URL.encode() in data:
                leftovers.append(f"microbot.cloud URL still in {n}")
        if leftovers:
            raise SystemExit("; ".join(leftovers))
    print("verified: all three patches present, jar intact")


if __name__ == "__main__":
    main()
