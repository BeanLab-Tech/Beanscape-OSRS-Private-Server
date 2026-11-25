'use strict';

const fs = require('node:fs');
const path = require('node:path');

const JAR_NAME = 'BeanscapeAdminPlugin.jar';
const TOKEN_PATTERN = /^bsa_[0-9a-f]{64}$/;

function paths(dataDir) {
  const runelite = path.join(dataDir, 'home', '.runelite');
  return {
    jar: path.join(runelite, 'microbot-plugins', JAR_NAME),
    settings: path.join(runelite, 'beanscape-admin.json'),
  };
}

function readSettings(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return {};
  }
}

/** What the launcher shows: nothing here ever returns the token itself. */
function status(dataDir, bundledJar) {
  const p = paths(dataDir);
  const settings = readSettings(p.settings);
  return {
    available: fs.existsSync(bundledJar),
    installed: fs.existsSync(p.jar),
    hasToken: TOKEN_PATTERN.test(settings.token || ''),
  };
}

function validToken(token) {
  return TOKEN_PATTERN.test(String(token || '').trim());
}

/** Copies the plugin into Microbot's sideload folder and writes the connection settings it reads. */
function install(dataDir, bundledJar, apiUrl, token) {
  const trimmed = String(token || '').trim();
  if (!validToken(trimmed)) {
    throw new Error('That does not look like an admin token. It starts with bsa_ and is 68 characters long.');
  }
  if (!fs.existsSync(bundledJar)) {
    throw new Error('The admin plugin is not bundled with this launcher build.');
  }
  const p = paths(dataDir);
  fs.mkdirSync(path.dirname(p.jar), { recursive: true });
  fs.copyFileSync(bundledJar, p.jar);
  fs.writeFileSync(p.settings, JSON.stringify({ apiUrl, token: trimmed }), { mode: 0o600 });
}

function remove(dataDir) {
  const p = paths(dataDir);
  fs.rmSync(p.jar, { force: true });
  fs.rmSync(p.settings, { force: true });
}

module.exports = { paths, status, install, remove, validToken, JAR_NAME };
