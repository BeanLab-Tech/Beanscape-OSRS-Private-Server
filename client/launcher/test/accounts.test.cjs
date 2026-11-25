'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const accounts = require('../electron/accounts.cjs');

function fakeStorage() {
  return {
    isEncryptionAvailable: () => true,
    encryptString: (value) => Buffer.from(`enc:${value}`),
    decryptString: (buf) => {
      const text = Buffer.from(buf).toString();
      if (!text.startsWith('enc:')) throw new Error('bad');
      return text.slice(4);
    },
  };
}

function setup() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'bs-acct-'));
}

test('legacy profile password round-trips the Microbot cipher', () => {
  const encoded = accounts.encryptMicrobotPassword('correct horse');
  assert.notStrictEqual(encoded, 'correct horse');
  assert.strictEqual(accounts.decryptMicrobotPassword(encoded), 'correct horse');
});

test('save stores an encrypted password and the public state omits it', () => {
  const dir = setup();
  const api = accounts.bind(fakeStorage());
  const state = api.save(dir, { username: 'Bean Smith', password: 's3cret-pass' });
  assert.strictEqual(state.accounts.length, 1);
  assert.strictEqual(state.accounts[0].username, 'bean smith');
  assert.strictEqual(state.accounts[0].hasPassword, true);
  assert.strictEqual(state.activeId, state.accounts[0].id);
  const raw = fs.readFileSync(path.join(dir, 'accounts.json'), 'utf8');
  assert.ok(!raw.includes('s3cret-pass'));
  assert.ok(!JSON.stringify(state).includes('s3cret-pass'));
});

test('prepareLaunch writes the Microbot profile into the isolated home', () => {
  const dir = setup();
  const home = path.join(dir, 'home');
  const api = accounts.bind(fakeStorage());
  api.save(dir, { username: 'oak', password: 'hunter2' });
  const args = api.prepareLaunch(dir, home);
  assert.deepStrictEqual(args, ['--profile', 'oak', '--clean-jagex-launcher']);
  const profiles = JSON.parse(
    fs.readFileSync(path.join(home, '.runelite', 'microbot-profiles', 'profiles.json'), 'utf8'),
  );
  assert.strictEqual(profiles.profiles.length, 1);
  assert.strictEqual(profiles.profiles[0].name, 'oak');
  assert.strictEqual(profiles.profiles[0].active, true);
  assert.strictEqual(accounts.decryptMicrobotPassword(profiles.profiles[0].password), 'hunter2');
  const props = fs.readFileSync(
    path.join(home, '.runelite', 'microbot-profiles', `oak-${profiles.profiles[0].id}.properties`),
    'utf8',
  );
  assert.match(props, /^runelite\.autologinplugin=true$/m);
});

test('a blank password on edit keeps the saved one', () => {
  const dir = setup();
  const api = accounts.bind(fakeStorage());
  const created = api.save(dir, { username: 'oak', password: 'first' });
  api.save(dir, { id: created.accounts[0].id, username: 'oak', password: '' });
  const argsHome = path.join(dir, 'home');
  api.prepareLaunch(dir, argsHome);
  const profiles = JSON.parse(
    fs.readFileSync(path.join(argsHome, '.runelite', 'microbot-profiles', 'profiles.json'), 'utf8'),
  );
  assert.strictEqual(accounts.decryptMicrobotPassword(profiles.profiles[0].password), 'first');
});

test('rejects a username the server would reject, and duplicates', () => {
  const dir = setup();
  const api = accounts.bind(fakeStorage());
  assert.throws(() => api.save(dir, { username: 'A', password: 'x' }), /Username/);
  assert.throws(() => api.save(dir, { username: 'Bad-Name', password: 'x' }), /Username/);
  api.save(dir, { username: 'oak', password: 'x' });
  assert.throws(() => api.save(dir, { username: 'Oak', password: 'y' }), /already/);
});

test('remove selects the next account and launch with none adds no args', () => {
  const dir = setup();
  const api = accounts.bind(fakeStorage());
  const first = api.save(dir, { username: 'oak', password: 'x' });
  api.save(dir, { username: 'pine', password: 'y' });
  const left = api.remove(dir, first.accounts[0].id);
  assert.strictEqual(left.accounts.length, 1);
  assert.strictEqual(left.accounts[0].username, 'pine');
  assert.strictEqual(left.activeId, left.accounts[0].id);
  api.remove(dir, left.accounts[0].id);
  assert.deepStrictEqual(api.prepareLaunch(dir, path.join(dir, 'home')), []);
});
