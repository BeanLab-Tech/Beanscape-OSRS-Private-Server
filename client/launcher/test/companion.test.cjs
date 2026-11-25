'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const snapshots = require('../electron/snapshots.cjs');
const settings = require('../electron/settings.cjs');
const updates = require('../electron/updates.cjs');
const sessions = require('../electron/sessions.cjs');
const history = require('../electron/history.cjs');
const feed = require('../electron/feed.cjs');

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'bsc-'));

test('a companion file is matched to the launcher account by normalized name', () => {
  const data = tmp();
  const dir = snapshots.companionDir(data);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(
    path.join(dir, 'bean_alt.json'),
    JSON.stringify({
      name: 'Bean Alt',
      combat: 40,
      total: 500,
      totalXp: 123456,
      skills: { attack: { level: 40, xp: 37224 } },
      wealth: { gp: 10, inventory: 20, equipment: 30, bank: 40 },
      accountType: 'IRONMAN',
      members: true,
      capturedAt: '2026-11-24T10:00:00Z',
    }),
  );
  fs.writeFileSync(path.join(dir, 'broken.json'), '{ half a file');
  const all = snapshots.readAll(dir);
  const snap = snapshots.forAccount(all, { username: 'bean alt' });
  assert.strictEqual(snap.combat, 40);
  assert.strictEqual(snap.skills.attack, 40);
  assert.strictEqual(snap.skillsXp.attack, 37224);
  assert.strictEqual(snap.wealth.total, 90);
  assert.strictEqual(snap.accountType, 'IRONMAN');
  assert.strictEqual(snapshots.forAccount(all, { username: 'nobody' }), null);
});

test('settings reject unknown ids and clamp memory', () => {
  const data = tmp();
  assert.deepStrictEqual(settings.read(data), settings.DEFAULTS);
  const saved = settings.save(data, { theme: 'azure', background: 'midnight', memoryMb: 99999 });
  assert.strictEqual(saved.theme, 'azure');
  assert.strictEqual(saved.background, 'midnight');
  assert.strictEqual(saved.memoryMb, settings.MEMORY.max);
  const bad = settings.save(data, { theme: '</style>', background: 'nope', memoryMb: 'x' });
  assert.strictEqual(bad.theme, 'azure');
  assert.strictEqual(bad.background, 'midnight');
  assert.strictEqual(bad.memoryMb, settings.MEMORY.max);
});

test('the changelog parses into days, entries and paragraphs', () => {
  const days = updates.parseChangelog('# Changelog\n\nintro\n\n## 2026-11-24\n\n### One\n\nfirst `code`.\nsecond.\n\n### Two\n\nbody\n\n## 2026-11-23\n\n### Old\n\nx\n');
  assert.deepStrictEqual(days.map((d) => d.date), ['2026-11-24', '2026-11-23']);
  assert.deepStrictEqual(days[0].entries.map((e) => e.title), ['One', 'Two']);
  assert.strictEqual(days[0].entries[0].paragraphs.length, 2);
});

test('the companion jar installs once and then reports up to date', () => {
  const bundled = tmp();
  const installed = path.join(tmp(), 'plugins');
  fs.writeFileSync(path.join(bundled, updates.COMPANION_JAR), 'v1');
  const check = () =>
    updates.pluginStatus({ name: 'c', jar: updates.COMPANION_JAR, bundledDir: bundled, installedDir: installed, manifest: {} }).state;
  assert.strictEqual(check(), 'not-installed');
  assert.strictEqual(updates.ensureCompanion(bundled, installed), true);
  assert.strictEqual(check(), 'up-to-date');
  assert.strictEqual(updates.ensureCompanion(bundled, installed), false);
  fs.writeFileSync(path.join(bundled, updates.COMPANION_JAR), 'v2');
  assert.strictEqual(check(), 'update');
});

test('cpu percent and room math', () => {
  assert.strictEqual(sessions.cpuPercent(10, 12, 4000, 4), 13);
  assert.strictEqual(sessions.cpuPercent(null, 12, 4000, 4), 0);
  assert.strictEqual(sessions.roomForClients(8192, 2048), 3);
  assert.strictEqual(sessions.roomForClients(500, 2048), 0);
});

test('history records a changed point and the feed derives milestones from it', () => {
  const dir = tmp();
  const snap = (xp, total, at) => ({ totalXp: xp, total, wealth: { total: 0, gp: 0 }, skillsXp: {}, liveAt: at });
  assert.ok(history.record(dir, 'Umpix', history.pointFromSnapshot(snap(900000, 99, '2026-11-20T10:00:00Z'))));
  assert.ok(history.record(dir, 'Umpix', history.pointFromSnapshot(snap(1100000, 101, '2026-11-21T10:00:00Z'))));
  assert.ok(!history.record(dir, 'Umpix', history.pointFromSnapshot(snap(1100000, 101, '2026-11-22T10:00:00Z'))));
  const items = feed.buildFeed({ events: [], points: history.query(dir, 'Umpix', 'all') });
  const titles = items.map((i) => i.title);
  assert.ok(titles.includes('Total level 100'));
  assert.ok(titles.includes('1M total XP'));
});
