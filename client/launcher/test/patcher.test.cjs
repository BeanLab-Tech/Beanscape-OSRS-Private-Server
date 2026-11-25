'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const AdmZip = require('adm-zip');
const { rewriteUtf8Constants, patchJar, JAGEX_MODULUS } = require('../electron/patcher.cjs');
const cfg = require('../electron/server-config.cjs');
const { parseJavaMajor } = require('../electron/java.cjs');

function utf8(s) {
  const b = Buffer.from(s, 'latin1');
  const head = Buffer.alloc(3);
  head[0] = 1;
  head.writeUInt16BE(b.length, 1);
  return Buffer.concat([head, b]);
}

// Minimal class-file prefix: magic, minor, major, constant pool, then a fake tail.
function fakeClass(strings, { withLong = false } = {}) {
  const entries = strings.map(utf8);
  let count = entries.length + 1;
  if (withLong) {
    entries.unshift(Buffer.from([5, 0, 0, 0, 0, 0, 0, 0, 1])); // CONSTANT_Long takes two slots
    count += 2;
  }
  const head = Buffer.alloc(10);
  head.writeUInt32BE(0xcafebabe, 0);
  head.writeUInt16BE(65, 6);
  head.writeUInt16BE(count, 8);
  return Buffer.concat([head, ...entries, Buffer.from('TAIL')]);
}

test('replaces a utf8 constant and keeps the rest intact', () => {
  const data = fakeClass(['keep', '127.0.0.1', 'also keep']);
  const out = rewriteUtf8Constants(data, new Map([['127.0.0.1', '']]));
  assert.ok(!out.includes('127.0.0.1'));
  assert.ok(out.includes('keep') && out.includes('also keep') && out.includes('TAIL'));
  assert.strictEqual(out.readUInt16BE(8), data.readUInt16BE(8));
});

test('walks past long/double slots', () => {
  const out = rewriteUtf8Constants(fakeClass(['x', 'target'], { withLong: true }), new Map([['target', 'new']]));
  assert.ok(out.includes('new') && !out.includes('target'));
});

test('throws when the constant is missing', () => {
  assert.throws(() => rewriteUtf8Constants(fakeClass(['a']), new Map([['nope', 'x']])), /not found exactly once/);
});

test('server modulus has the same shape as the Jagex one', () => {
  assert.strictEqual(cfg.modulus.length, JAGEX_MODULUS.length);
  assert.match(cfg.modulus, /^[0-9a-f]+$/);
});

test('patchJar swaps both constants and refuses a wrong checksum', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bsc-'));
  const src = path.join(dir, 'in.jar');
  const dst = path.join(dir, 'out.jar');
  const zip = new AdmZip();
  zip.addFile('bc.class', fakeClass([JAGEX_MODULUS, 'x']));
  zip.addFile('bl.class', fakeClass(['127.0.0.1', 'y']));
  zip.addFile('other.txt', Buffer.from('untouched'));
  zip.writeZip(src);
  const sha = crypto.createHash('sha256').update(fs.readFileSync(src)).digest('hex');

  assert.throws(() => patchJar(src, dst, cfg.modulus, 'deadbeef'), /refusing to patch/);
  patchJar(src, dst, cfg.modulus, sha);

  const out = new AdmZip(dst);
  const bc = out.getEntry('bc.class').getData();
  assert.ok(bc.includes(cfg.modulus) && !bc.includes(JAGEX_MODULUS));
  assert.ok(!out.getEntry('bl.class').getData().includes('127.0.0.1'));
  assert.strictEqual(out.getEntry('other.txt').getData().toString(), 'untouched');
});

test('parseJavaMajor handles old and new version strings', () => {
  assert.strictEqual(parseJavaMajor('openjdk version "21.0.3" 2024-04-16'), 21);
  assert.strictEqual(parseJavaMajor('java version "1.8.0_402"'), 8);
  assert.strictEqual(parseJavaMajor('garbage'), 0);
});
