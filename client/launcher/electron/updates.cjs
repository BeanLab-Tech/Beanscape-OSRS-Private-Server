'use strict';

// Updates tab: every Beanscape change (from CHANGELOG.md) plus the state of each installed piece.

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const COMPANION_JAR = 'BeanscapeCompanion.jar';

/**
 * "## 2026-11-24" starts a day, "### Title" starts an entry, and the lines under it are its text.
 * Paragraphs are kept apart so the view can render them as separate blocks.
 */
function parseChangelog(md) {
  const days = [];
  let day = null;
  let entry = null;
  for (const line of String(md || '').split(/\r?\n/)) {
    const d = line.match(/^##\s+(.+?)\s*$/);
    if (d && !line.startsWith('###')) {
      day = { date: d[1], entries: [] };
      days.push(day);
      entry = null;
      continue;
    }
    const e = line.match(/^###\s+(.+?)\s*$/);
    if (e && day) {
      entry = { title: e[1], paragraphs: [] };
      day.entries.push(entry);
      continue;
    }
    if (entry && line.trim()) entry.paragraphs.push(line.trim());
  }
  return days;
}

function readChangelog(file) {
  try {
    return parseChangelog(fs.readFileSync(file, 'utf8'));
  } catch {
    return [];
  }
}

function sha1(file) {
  try {
    return crypto.createHash('sha1').update(fs.readFileSync(file)).digest('hex');
  } catch {
    return null;
  }
}

function readJson(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return fallback;
  }
}

/** A bundled plugin jar against the copy in the client's plugin folder. */
function pluginStatus({ name, jar, bundledDir, installedDir, manifest }) {
  const bundled = path.join(bundledDir, jar);
  const installed = path.join(installedDir, jar);
  const version = manifest[jar] || null;
  if (!fs.existsSync(bundled)) return { name, jar, version, state: 'missing', installed: false };
  if (!fs.existsSync(installed)) return { name, jar, version, state: 'not-installed', installed: false };
  const same = sha1(bundled) === sha1(installed);
  return { name, jar, version, state: same ? 'up-to-date' : 'update', installed: true };
}

/** Copies the bundled companion jar into the client's plugin folder when it is missing or older. */
function ensureCompanion(bundledDir, installedDir) {
  const bundled = path.join(bundledDir, COMPANION_JAR);
  if (!fs.existsSync(bundled)) return false;
  const installed = path.join(installedDir, COMPANION_JAR);
  if (sha1(bundled) === sha1(installed)) return false;
  fs.mkdirSync(installedDir, { recursive: true });
  fs.copyFileSync(bundled, installed);
  return true;
}

function build({ changelogFile, pluginsDir, installedDir, launcherVersion, microbotVersion, adminInstalled }) {
  const manifest = readJson(path.join(pluginsDir, 'manifest.json'), {});
  const companion = pluginStatus({
    name: 'Beanscape Companion',
    jar: COMPANION_JAR,
    bundledDir: pluginsDir,
    installedDir,
    manifest,
  });
  const admin = pluginStatus({
    name: 'Admin console',
    jar: 'BeanscapeAdminPlugin.jar',
    bundledDir: pluginsDir,
    installedDir,
    manifest,
  });
  // The admin console is opt-in (it needs a token), so "not installed" is not an update.
  if (!adminInstalled && admin.state === 'not-installed') admin.state = 'optional';
  return {
    changelog: readChangelog(changelogFile),
    launcher: { version: launcherVersion },
    microbot: { version: microbotVersion },
    plugins: [companion, admin],
  };
}

module.exports = { parseChangelog, readChangelog, pluginStatus, ensureCompanion, build, COMPANION_JAR };
