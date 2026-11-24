#!/usr/bin/env python3
"""Patch Microbot 2.0.20 (RuneLite 1.11.19, OSRS rev 233) to log in to the BeanLab server directly.

Usage:
    python patch-microbot.py microbot-2.0.20.jar microbot-2.0.20-beanlab.jar <server modulus hex>

The modulus is the `modulus:` value of the BeanLab target in ~/.rsprox/proxy-targets.yaml, i.e.
the public half of the server's RSA login key (.data/game.key on the VM). It is not secret.

Exactly two constants change (the same two RSProx changes when it patches RuneLite):
  bc.class  Jagex's RSA login modulus  ->  the BeanLab server's modulus
  bl.class  host allowlist entry "127.0.0.1" -> ""  (endsWith("") lets the client accept any host)
The game port stays 43594, and the server address comes from --jav_config at launch.
Every other jar entry is copied byte for byte.
"""

import hashlib
import struct
import sys
import zipfile

EXPECTED_SHA256 = "95a652d44afe867b5b18879ef5ddf56efb0f0a43b005e26c7018eafde8587dd6"  # 2.0.20
JAGEX_MODULUS = (
    "8b3a6290fcfe8f8d0752bfd815158328bf43b89e91356380968f51465f29f689dde6c169375e12dd28a43633b6"
    "19a48f71c539c9f1f4e59aadd5e2ad681f3e1270313ef044889faee740f641ea0f8b341d44f388758e12b893f4"
    "6fb7515ed8dc9b6ded076f1a621cf0d6f2157a32434e5f3028e3baf3ff2f08ab86de8776638f"
)

# Constant-pool entry sizes (excluding the tag byte) for non-UTF8 tags.
_CP_SIZES = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 16: 2, 19: 2, 20: 2, 9: 4, 10: 4, 11: 4, 12: 4,
             17: 4, 18: 4, 15: 3}


def rewrite_utf8_constants(data: bytes, replacements: dict[bytes, bytes]) -> bytes:
    """Replaces whole CONSTANT_Utf8 entries; each key must occur exactly once."""
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
            if tag in (5, 6):  # long/double take two slots
                k += 1
        k += 1
    bad = {key.decode("latin1")[:20]: n for key, n in hits.items() if n != 1}
    if bad:
        raise SystemExit(f"constant not found exactly once (wrong jar?): {bad}")
    return bytes(out + data[i:])


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    src, dst, modulus = sys.argv[1], sys.argv[2], sys.argv[3].strip().lower()
    if len(modulus) != len(JAGEX_MODULUS) or any(c not in "0123456789abcdef" for c in modulus):
        raise SystemExit(f"modulus must be {len(JAGEX_MODULUS)} hex chars (1024-bit key)")

    with open(src, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    if digest != EXPECTED_SHA256:
        raise SystemExit(f"{src} is not Microbot 2.0.20 (sha256 {digest}); refusing to patch")

    patches = {
        "bc.class": {JAGEX_MODULUS.encode(): modulus.encode()},
        "bl.class": {b"127.0.0.1": b""},
    }
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if info.filename in patches:
                data = rewrite_utf8_constants(data, patches[info.filename])
                print("patched", info.filename)
            zout.writestr(info, data, compress_type=info.compress_type)
    print("wrote", dst)


if __name__ == "__main__":
    main()
