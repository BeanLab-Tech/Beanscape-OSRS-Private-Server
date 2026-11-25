'use strict';

// Account feed: game events from Beanscape Companion plus milestones worked out from history.

const fs = require("node:fs");
const path = require("node:path");

const XP_99 =13_034_431;
const TOTAL_XP_MARKS = [1e6, 5e6, 10e6, 25e6, 50e6, 100e6, 200e6, 500e6, 1e9];
const WEALTH_MARKS = [1e6, 10e6, 100e6, 1e9];

// XP needed for each level, from the OSRS formula.
const XP_TABLE = (() => {
  const table = [0, 0];
  let points = 0;
  for (let level = 1; level < 99; level++) {
    points += Math.floor(level + 300 * 2 ** (level / 7));
    table.push(Math.floor(points / 4));
  }
  return table; // table[n] = xp for level n
})();

function levelForXp(xp) {
  const v = Number(xp) || 0;
  let level = 1;
  for (let n = 2; n <= 99; n++) {
    if (v >= XP_TABLE[n]) level = n;
    else break;
  }
  return level;
}

/** Same file name rule as the Companion plugin (spaces, _ and - fold to _). */
function eventKey(name) {
  return String(name || "")
    .trim()
    .toLowerCase()
    .replace(/[\s_\- ]+/g, "_");
}

function readEvents(dir, name) {
  let text = "";
  try {
    text = fs.readFileSync(path.join(dir, `${eventKey(name)}.jsonl`), "utf8");
  } catch {
    return [];
  }
  const out = [];
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue;
    try {
      const e = JSON.parse(line);
      if (e && e.type && Number.isFinite(Date.parse(e.t))) out.push({ ...e, source: "game" });
    } catch {
      // torn line: skip
    }
  }
  return out;
}

function label(n) {
  if (n >= 1e9) return `${n / 1e9}B`;
  if (n >= 1e6) return `${n / 1e6}M`;
  return String(n);
}

function crossed(before, after, mark) {
  return Number.isFinite(before) && Number.isFinite(after) && before < mark && after >= mark;
}

function num(v) {
  return v === null || v === undefined ? NaN : Number(v);
}

function capitalize(text) {
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : text;
}

/** Milestones between consecutive history points (oldest first). */
function milestones(points) {
  const out = [];
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1];
    const b = points[i];
    const t = b.t;
    const ta = num(a.total);
    const tb = num(b.total);
    if (Number.isFinite(ta) && Number.isFinite(tb)) {
      for (let k = Math.floor(ta / 100) + 1; k * 100 <= tb; k++) {
        out.push({ t, type: "milestone", title: `Total level ${k * 100}`, source: "history" });
      }
    }
    for (const mark of TOTAL_XP_MARKS) {
      if (crossed(num(a.totalXp), num(b.totalXp), mark)) {
        out.push({ t, type: "milestone", title: `${label(mark)} total XP`, source: "history" });
      }
    }
    for (const mark of WEALTH_MARKS) {
      if (crossed(num(a.wealth), num(b.wealth), mark)) {
        out.push({ t, type: "milestone", title: `Wealth passed ${label(mark)}`, source: "history" });
      }
    }
    for (const [skill, xp] of Object.entries(b.skillsXp || {})) {
      if (crossed(num(a.skillsXp?.[skill]), num(xp), XP_99)) {
        out.push({ t, type: "milestone", title: `99 ${capitalize(skill)}`, skill, source: "history" });
      }
    }
  }
  return out;
}

/** Level-ups worked out from XP in history, for characters without Companion level events. */
function derivedLevels(points) {
  const out = [];
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1].skillsXp || {};
    const b = points[i].skillsXp || {};
    for (const [skill, xp] of Object.entries(b)) {
      if (a[skill] == null) continue;
      const from = levelForXp(a[skill]);
      const to = levelForXp(xp);
      for (let level = from + 1; level <= to; level++) {
        out.push({ t: points[i].t, type: "level", title: `${capitalize(skill)} ${level}`, skill, level, source: "history" });
      }
    }
  }
  return out;
}

/** Newest first. Companion level events win; history fills in only when there are none. */
function buildFeed({ events = [], points = [], limit = 50 }) {
  const sorted = [...points].sort((x, y) => Date.parse(x.t) - Date.parse(y.t));
  const hasGameLevels = events.some((e) => e.type === "level");
  const all = [...events, ...milestones(sorted), ...(hasGameLevels ? [] : derivedLevels(sorted))];
  return all.sort((x, y) => Date.parse(y.t) - Date.parse(x.t)).slice(0, limit);
}

module.exports = { levelForXp, eventKey, readEvents, milestones, buildFeed };
