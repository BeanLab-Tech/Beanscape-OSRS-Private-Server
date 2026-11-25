'use strict';

// Per-character progress history: one JSON line per point in <dir>/<key>.jsonl.

const fs = require("node:fs");
const path = require("node:path");

const MIN_GAP_MS = 30 * 60 * 1000;
const MAX_POINTS = 120;
const RANGES = { "7d": 7 * 86400_000, "30d": 30 * 86400_000 };

function keyFor(name) {
  return String(name || "")
    .trim()
    .toLowerCase()
    .replace(/\s+/g, "_");
}

function fileFor(dir, name) {
  return path.join(dir, `${keyFor(name)}.jsonl`);
}

function readAll(dir, name) {
  let text = "";
  try {
    text = fs.readFileSync(fileFor(dir, name), "utf8");
  } catch {
    return [];
  }
  const out = [];
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue;
    try {
      const p = JSON.parse(line);
      if (p && Number.isFinite(Date.parse(p.t))) out.push(p);
    } catch {
      // a torn line from a crash: skip it
    }
  }
  return out.sort((a, b) => Date.parse(a.t) - Date.parse(b.t));
}

function known(value) {
  const n = Number(value);
  return value === null || value === undefined || !Number.isFinite(n) || n <= 0 ? null : n;
}

function wealthOf(wealth) {
  if (!wealth || typeof wealth !== "object") return null;
  const total = known(wealth.total);
  if (total != null) return total;
  const gp = known(wealth.gp);
  if (gp != null) return gp;
  return 0;
}

/** Build a history point from an account-cache snapshot, stamped with when it was captured. */
function pointFromSnapshot(snapshot, t) {
  if (!snapshot) return null;
  const at = t || snapshot.liveAt || snapshot.hiscoresAt || new Date().toISOString();
  return {
    t: new Date(at).toISOString(),
    totalXp: known(snapshot.totalXp),
    total: known(snapshot.total),
    wealth: wealthOf(snapshot.wealth),
    skillsXp: snapshot.skillsXp || {},
  };
}

function same(a, b) {
  return a.totalXp === b.totalXp && a.total === b.total && a.wealth === b.wealth;
}

/** Appends a point unless it repeats the last one or comes too soon without a level-up. */
function record(dir, name, point) {
  if (!name || !point || !(point.totalXp || point.total || point.wealth)) return false;
  const all = readAll(dir, name);
  const last = all[all.length - 1];
  if (last) {
    if (same(last, point)) return false;
    const gap = Date.parse(point.t) - Date.parse(last.t);
    if (gap <= 0) return false;
    if (gap < MIN_GAP_MS && point.total === last.total) return false;
  }
  fs.mkdirSync(dir, { recursive: true });
  fs.appendFileSync(fileFor(dir, name), `${JSON.stringify(point)}\n`);
  return true;
}

function downsample(points, max = MAX_POINTS) {
  if (points.length <= max) return points;
  const start = Date.parse(points[0].t);
  const span = Date.parse(points[points.length - 1].t) - start || 1;
  const buckets = new Map();
  for (const p of points) {
    const i = Math.min(max - 1, Math.floor(((Date.parse(p.t) - start) / span) * max));
    buckets.set(i, p);
  }
  return [...buckets.keys()].sort((a, b) => a - b).map((i) => buckets.get(i));
}

/** Points in range ("7d" | "30d" | "all"), oldest first, at most 120. */
function query(dir, name, range = "30d", now = Date.now()) {
  const span = RANGES[range];
  const all = readAll(dir, name);
  const inRange = span ? all.filter((p) => Date.parse(p.t) >= now - span) : all;
  return downsample(inRange);
}

module.exports = { record, query, pointFromSnapshot, keyFor };
