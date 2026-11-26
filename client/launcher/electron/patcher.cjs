'use strict';

// Node port of BeanLab's client/microbot/patch-microbot.py.
// Exactly two constants change inside the pinned Microbot jar (class names come from
// server-config.cjs `patch`, because they change every revision):
//   modulusClass    Jagex's RSA login modulus -> the server's modulus
//   allowlistClass  host allowlist entry "127.0.0.1" -> ""  (endsWith("") accepts any host)
// Every other jar entry is copied byte for byte.

const crypto = require('node:crypto');
const fs = require('node:fs');
const AdmZip = require('adm-zip');

const JAGEX_MODULUS =
  '8b3a6290fcfe8f8d0752bfd815158328bf43b89e91356380968f51465f29f689dde6c169375e12dd28a43633b619a48f71c539c9f1f4e59aadd5e2ad681f3e1270313ef044889faee740f641ea0f8b341d44f388758e12b893f46fb7515ed8dc9b6ded076f1a621cf0d6f2157a32434e5f3028e3baf3ff2f08ab86de8776638f';

// Constant-pool entry sizes (excluding the tag byte) for non-UTF8 tags.
const CP_SIZES = { 3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2 };

/**
 * Replaces whole CONSTANT_Utf8 entries of a class file. `replacements` is a Map of
 * old string -> new string; each key must occur exactly once or this throws.
 */
function rewriteUtf8Constants(data, replacements) {
  const count = data.readUInt16BE(8);
  const chunks = [data.subarray(0, 10)];
  const hits = new Map([...replacements.keys()].map((k) => [k, 0]));
  let i = 10;
  for (let k = 1; k < count; k++) {
    const tag = data[i];
    if (tag === 1) {
      const length = data.readUInt16BE(i + 1);
      const end = i + 3 + length;
      const key = data.subarray(i + 3, end).toString('latin1');
      if (replacements.has(key)) {
        const next = Buffer.from(replacements.get(key), 'latin1');
        const head = Buffer.alloc(3);
        head[0] = 1;
        head.writeUInt16BE(next.length, 1);
        chunks.push(head, next);
        hits.set(key, hits.get(key) + 1);
      } else {
        chunks.push(data.subarray(i, end));
      }
      i = end;
    } else {
      const size = CP_SIZES[tag];
      if (size === undefined) throw new Error(`unknown constant-pool tag ${tag}`);
      chunks.push(data.subarray(i, i + 1 + size));
      i += 1 + size;
      if (tag === 5 || tag === 6) k++; // long/double take two slots
    }
  }
  const bad = [...hits].filter(([, n]) => n !== 1).map(([key]) => key.slice(0, 20));
  if (bad.length) throw new Error(`constant not found exactly once (wrong jar?): ${bad.join(', ')}`);
  chunks.push(data.subarray(i));
  return Buffer.concat(chunks);
}

function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function validModulus(modulus) {
  return typeof modulus === 'string' && modulus.length === JAGEX_MODULUS.length && /^[0-9a-f]+$/.test(modulus);
}

// Microbot 2.0.20 (rev 233); other builds pass their own `targets`.
const DEFAULT_TARGETS = { modulusClass: 'bc.class', allowlistClass: 'bl.class', jagexModulus: JAGEX_MODULUS };

/** Patches `src` into `dst`. Refuses any jar that is not the expected Microbot build. */
function patchJar(src, dst, modulus, expectedSha256, targets = DEFAULT_TARGETS) {
  modulus = String(modulus).trim().toLowerCase();
  if (!validModulus(modulus)) throw new Error(`modulus must be ${JAGEX_MODULUS.length} hex chars (1024-bit key)`);
  const digest = sha256File(src);
  if (digest !== expectedSha256) throw new Error(`${src} is not the expected Microbot jar (sha256 ${digest}); refusing to patch`);

  const patches = {
    [targets.modulusClass]: new Map([[targets.jagexModulus, modulus]]),
    [targets.allowlistClass]: new Map([['127.0.0.1', '']]),
  };
  const zin = new AdmZip(src);
  const zout = new AdmZip();
  const patched = [];
  for (const entry of zin.getEntries()) {
    if (entry.isDirectory) {
      zout.addFile(entry.entryName, Buffer.alloc(0));
      continue;
    }
    let data = entry.getData();
    if (patches[entry.entryName]) {
      data = rewriteUtf8Constants(data, patches[entry.entryName]);
      patched.push(entry.entryName);
    }
    zout.addFile(entry.entryName, data);
  }
  if (patched.length !== Object.keys(patches).length) throw new Error('expected classes missing from jar (wrong jar?)');
  const tmp = `${dst}.tmp`;
  zout.writeZip(tmp);
  fs.renameSync(tmp, dst);
  return patched;
}

module.exports = { JAGEX_MODULUS, rewriteUtf8Constants, patchJar, sha256File, validModulus };
