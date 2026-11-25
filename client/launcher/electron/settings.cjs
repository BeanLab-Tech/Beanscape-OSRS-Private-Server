'use strict';

const fs = require('node:fs');
const path = require('node:path');

// Ids only: the renderer owns the colors, so a bad file can never inject CSS.
const THEMES = ['gold', 'emerald', 'crimson', 'azure', 'amethyst', 'ember', 'bone'];
const BACKGROUNDS = ['stone', 'ink', 'midnight', 'forest', 'plum', 'slate'];
const DEFAULTS = { theme: 'gold', background: 'stone', memoryMb: 2048 };
const MEMORY = { min: 1024, max: 4096, step: 128 };

function file(dataDir) {
  return path.join(dataDir, 'settings.json');
}

function clampMemory(value, fallback) {
  const n = Math.round(Number(value) / MEMORY.step) * MEMORY.step;
  if (!Number.isFinite(n)) return fallback;
  return Math.min(MEMORY.max, Math.max(MEMORY.min, n));
}

function normalize(raw, base = DEFAULTS) {
  return {
    theme: THEMES.includes(raw?.theme) ? raw.theme : base.theme,
    background: BACKGROUNDS.includes(raw?.background) ? raw.background : base.background,
    memoryMb: clampMemory(raw?.memoryMb ?? base.memoryMb, base.memoryMb),
  };
}

function read(dataDir) {
  try {
    return normalize(JSON.parse(fs.readFileSync(file(dataDir), 'utf8')));
  } catch {
    return { ...DEFAULTS };
  }
}

function save(dataDir, patch) {
  const next = normalize({ ...read(dataDir), ...patch }, read(dataDir));
  fs.mkdirSync(dataDir, { recursive: true });
  fs.writeFileSync(file(dataDir), JSON.stringify(next, null, 2));
  return next;
}

module.exports = { read, save, normalize, THEMES, BACKGROUNDS, DEFAULTS, MEMORY };
