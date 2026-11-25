'use strict';

// Reads what the Beanscape Companion plugin writes for each character:
// <home>/.runelite/beanscape/companion/<name>.json (stats) and events/<name>.jsonl (feed).

const fs = require('node:fs');
const path = require('node:path');

function companionDir(dataDir) {
  return path.join(dataDir, 'home', '.runelite', 'beanscape', 'companion');
}

/** Same file-name rule as the plugin: trim, lowercase, spaces/underscores/hyphens fold to one underscore. */
function keyFor(name) {
  return String(name || '')
    .trim()
    .toLowerCase()
    .replace(/[\s_\- ]+/g, '_');
}

function numOrNull(value) {
  const n = Number(value);
  return value === null || value === undefined || !Number.isFinite(n) ? null : n;
}

function readOne(file) {
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  const name = String(raw?.name || '').trim();
  if (!name) return null;
  const skills = {};
  const skillsXp = {};
  for (const [skill, value] of Object.entries(raw.skills || {})) {
    skills[skill] = Number(value?.level) || 1;
    skillsXp[skill] = Number(value?.xp) || 0;
  }
  const wealth = raw.wealth || {};
  const parts = (Number(wealth.inventory) || 0) + (Number(wealth.equipment) || 0) + (Number(wealth.bank) || 0);
  return {
    name,
    combat: numOrNull(raw.combat),
    total: numOrNull(raw.total),
    totalXp: numOrNull(raw.totalXp),
    skills,
    skillsXp,
    wealth: {
      gp: Number(wealth.gp) || 0,
      inventory: Number(wealth.inventory) || 0,
      equipment: Number(wealth.equipment) || 0,
      bank: Number(wealth.bank) || 0,
      total: Number(wealth.total) || parts,
      bankSeenAt: wealth.bankSeenAt || null,
    },
    accountType: raw.accountType && raw.accountType !== 'UNKNOWN' ? String(raw.accountType) : null,
    members: typeof raw.members === 'boolean' ? raw.members : null,
    membershipDays: numOrNull(raw.membershipDays),
    playtimeMinutes: numOrNull(raw.playtimeMinutes),
    world: numOrNull(raw.world),
    questPoints: numOrNull(raw.questPoints),
    liveAt: raw.capturedAt || null,
  };
}

/** Every character file in the folder, keyed by normalized name. Broken or half-written files are skipped. */
function readAll(dir) {
  const out = {};
  let files = [];
  try {
    files = fs.readdirSync(dir).filter((name) => name.toLowerCase().endsWith('.json'));
  } catch {
    return out;
  }
  for (const file of files) {
    try {
      const snap = readOne(path.join(dir, file));
      if (snap) out[keyFor(snap.name)] = snap;
    } catch {
      // keep the rest
    }
  }
  return out;
}

/** The snapshot for a launcher account, or null until that character has logged in with the plugin. */
function forAccount(all, account) {
  return all[keyFor(account?.username)] || null;
}

module.exports = { companionDir, keyFor, readAll, readOne, forAccount };
