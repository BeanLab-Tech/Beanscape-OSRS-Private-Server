'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const admin = require('../electron/admin.cjs');

const TOKEN = `bsa_${'ab'.repeat(32)}`;

function setup() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bsa-'));
  const jar = path.join(dir, 'bundled.jar');
  fs.writeFileSync(jar, 'jar-bytes');
  return { dir: path.join(dir, 'data'), jar };
}

test('install copies the jar and writes settings, and status never leaks the token', () => {
  const { dir, jar } = setup();
  assert.deepStrictEqual(admin.status(dir, jar), { available: true, installed: false, hasToken: false });
  admin.install(dir, jar, 'https://example.test/adminapi', TOKEN);
  const p = admin.paths(dir);
  assert.strictEqual(fs.readFileSync(p.jar, 'utf8'), 'jar-bytes');
  assert.deepStrictEqual(JSON.parse(fs.readFileSync(p.settings, 'utf8')), { apiUrl: 'https://example.test/adminapi', token: TOKEN });
  const status = admin.status(dir, jar);
  assert.deepStrictEqual(status, { available: true, installed: true, hasToken: true });
  assert.ok(!JSON.stringify(status).includes(TOKEN));
});

test('install rejects tokens that are not in the issued format', () => {
  const { dir, jar } = setup();
  for (const bad of ['', 'nope', 'bsa_short', `bsa_${'zz'.repeat(32)}`, `${TOKEN}x`]) {
    assert.throws(() => admin.install(dir, jar, 'https://x', bad), /admin token/);
  }
  assert.ok(!fs.existsSync(admin.paths(dir).jar));
});

test('install fails clearly when the plugin is not bundled', () => {
  const { dir } = setup();
  assert.throws(() => admin.install(dir, path.join(dir, 'missing.jar'), 'https://x', TOKEN), /not bundled/);
});

test('remove deletes the plugin and the saved token', () => {
  const { dir, jar } = setup();
  admin.install(dir, jar, 'https://x', TOKEN);
  admin.remove(dir);
  assert.deepStrictEqual(admin.status(dir, jar), { available: true, installed: false, hasToken: false });
});
