'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const USERNAME_RE = /^[a-z0-9_ ]{2,12}$/;
const MICROBOT_KEY = Buffer.from('microbot12345678');

function storePath(userDataDir) {
  return path.join(userDataDir, 'accounts.json');
}

function emptyStore() {
  return { activeId: null, accounts: [] };
}

function readStore(userDataDir) {
  try {
    const parsed = JSON.parse(fs.readFileSync(storePath(userDataDir), 'utf8'));
    return {
      activeId: parsed.activeId || null,
      accounts: Array.isArray(parsed.accounts) ? parsed.accounts : [],
    };
  } catch {
    return emptyStore();
  }
}

function writeStore(userDataDir, store) {
  fs.mkdirSync(userDataDir, { recursive: true });
  const dest = storePath(userDataDir);
  const tmp = `${dest}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(store, null, 2));
  fs.renameSync(tmp, dest);
}

/** Microbot stores legacy passwords as AES-128-ECB, key "microbot12345678". */
function encryptMicrobotPassword(plain) {
  const cipher = crypto.createCipheriv('aes-128-ecb', MICROBOT_KEY, null);
  return Buffer.concat([cipher.update(String(plain), 'utf8'), cipher.final()]).toString('base64');
}

function decryptMicrobotPassword(payload) {
  const decipher = crypto.createDecipheriv('aes-128-ecb', MICROBOT_KEY, null);
  return Buffer.concat([decipher.update(Buffer.from(payload, 'base64')), decipher.final()]).toString('utf8');
}

function upsertProperty(file, key, value) {
  let text = '';
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    text = '';
  }
  const line = `${key}=${value}`;
  const re = new RegExp(`^${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}=.*$`, 'm');
  if (re.test(text)) text = text.replace(re, line);
  else text = `${text.replace(/\s*$/, '')}${text.trim() ? '\n' : ''}${line}\n`;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text);
}

// Microbot's AutoLogin world; the properties file spells the key "World\ Preferred".
const AUTOLOGIN_WORLD_KEY = 'AutoLoginConfig.World\\ Preferred';

/**
 * Writes the active username into the isolated Microbot home and returns client args. `world`
 * pins AutoLogin to the server's world; without it AutoLogin can pick a real Jagex world.
 */
function writeLegacyProfile(homeDir, username, password, world) {
  const dir = path.join(homeDir, '.runelite', 'microbot-profiles');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, 'profiles.json');
  let store = { profiles: [] };
  try {
    store = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    store = { profiles: [] };
  }
  if (!Array.isArray(store.profiles)) store.profiles = [];

  let target = store.profiles.find((item) => item.name === username);
  if (!target) {
    target = {
      id: Date.now() * 1000 + Math.floor(Math.random() * 1000),
      name: username,
      memberExpireDaysTimeStemp: 0,
      memberExpireDays: 0,
      isMember: false,
      sync: false,
      active: false,
      rev: -1,
      defaultForRsProfiles: [],
    };
    store.profiles.push(target);
  }
  target.password = encryptMicrobotPassword(password);
  target.active = true;
  for (const item of store.profiles) {
    if (item !== target) item.active = false;
  }
  fs.writeFileSync(file, JSON.stringify(store));
  upsertProperty(
    path.join(dir, `${username}-${target.id}.properties`),
    'runelite.autologinplugin',
    'true',
  );
  if (Number.isInteger(world) && world > 0) {
    upsertProperty(path.join(dir, `${username}-${target.id}.properties`), AUTOLOGIN_WORLD_KEY, String(world));
  }
  return ['--profile', username, '--clean-jagex-launcher'];
}

function bind(safeStorage) {
  function encryptSecret(value) {
    if (!safeStorage.isEncryptionAvailable()) {
      throw new Error('Windows encryption is unavailable, so the password was not stored.');
    }
    return safeStorage.encryptString(value).toString('base64');
  }

  function decryptSecret(payload) {
    if (!payload || !safeStorage.isEncryptionAvailable()) return '';
    try {
      return safeStorage.decryptString(Buffer.from(payload, 'base64'));
    } catch {
      return '';
    }
  }

  function repairActive(store) {
    const valid = store.accounts.some((item) => item.id === store.activeId);
    if (valid || (!store.accounts.length && !store.activeId)) return false;
    store.activeId = store.accounts[0] ? store.accounts[0].id : null;
    return true;
  }

  function publicState(userDataDir) {
    const store = readStore(userDataDir);
    if (repairActive(store)) writeStore(userDataDir, store);
    return {
      activeId: store.activeId,
      accounts: store.accounts.map((item) => ({
        id: item.id,
        username: item.username,
        hasPassword: Boolean(item.passwordEnc),
      })),
    };
  }

  function save(userDataDir, payload) {
    const username = String(payload.username || '').trim().toLowerCase();
    if (!USERNAME_RE.test(username)) {
      throw new Error('Username must be 2 to 12 lowercase letters, numbers, spaces, or underscores.');
    }
    const store = readStore(userDataDir);
    const duplicate = store.accounts.find(
      (item) => item.username === username && item.id !== payload.id,
    );
    if (duplicate) throw new Error('That username is already in the launcher.');

    const password = typeof payload.password === 'string' ? payload.password : '';
    let account = payload.id ? store.accounts.find((item) => item.id === payload.id) : null;
    if (payload.id && !account) throw new Error('Account not found.');
    if (!account) {
      if (!password) throw new Error('Enter a password.');
      account = { id: crypto.randomUUID(), createdAt: new Date().toISOString() };
      store.accounts.push(account);
    } else if (!password && !account.passwordEnc) {
      throw new Error('Enter a password.');
    }
    account.username = username;
    if (password) account.passwordEnc = encryptSecret(password);
    account.updatedAt = new Date().toISOString();
    if (!store.activeId) store.activeId = account.id;
    writeStore(userDataDir, store);
    return publicState(userDataDir);
  }

  function select(userDataDir, id) {
    const store = readStore(userDataDir);
    if (!store.accounts.some((item) => item.id === id)) throw new Error('Account not found.');
    store.activeId = id;
    writeStore(userDataDir, store);
    return publicState(userDataDir);
  }

  function remove(userDataDir, id) {
    const store = readStore(userDataDir);
    if (!store.accounts.some((item) => item.id === id)) throw new Error('Account not found.');
    store.accounts = store.accounts.filter((item) => item.id !== id);
    if (store.activeId === id) store.activeId = store.accounts[0] ? store.accounts[0].id : null;
    writeStore(userDataDir, store);
    return publicState(userDataDir);
  }

  function prepareLaunch(userDataDir, homeDir, world) {
    const store = readStore(userDataDir);
    const account = store.accounts.find((item) => item.id === store.activeId);
    if (!account) return [];
    const password = decryptSecret(account.passwordEnc);
    if (!password) {
      throw new Error(`Could not read the password for ${account.username}. Edit the account and enter it again.`);
    }
    return writeLegacyProfile(homeDir, account.username, password, world);
  }

  return { publicState, save, select, remove, prepareLaunch };
}

module.exports = {
  bind,
  encryptMicrobotPassword,
  decryptMicrobotPassword,
  writeLegacyProfile,
  USERNAME_RE,
};
