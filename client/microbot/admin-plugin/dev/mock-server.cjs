'use strict';

// Local stand-in for the server's /v1 admin API, used to develop and test the plugin UI.
// Run: node mock/mock-server.cjs   (listens on 127.0.0.1:8099, token "dev-token")
const http = require('node:http');

const TOKEN = 'dev-token';
const ME = { name: 'admin', modlevel: process.env.MOCK_LEVEL || 'owner' };
const SKILLS = ['Attack', 'Defence', 'Strength', 'Hitpoints', 'Ranged', 'Prayer', 'Magic', 'Cooking', 'Woodcutting', 'Fletching', 'Fishing', 'Firemaking', 'Crafting', 'Smithing', 'Mining', 'Herblore', 'Agility', 'Thieving', 'Slayer', 'Farming', 'Runecraft', 'Hunter', 'Construction'];
const players = [
  { name: 'admin', combat: 126, modlevel: 'owner', area: 'Lumbridge', coords: '3222, 3218, 0' },
  { name: 'Umpix', combat: 98, modlevel: 'admin', area: 'Draynor Village', coords: '3093, 3244, 0' },
  { name: 'skywarp45', combat: 62, modlevel: 'player', area: 'Draynor willows', coords: '3086, 3235, 0' },
  { name: 'bean_one', combat: 34, modlevel: 'player', area: 'Grand Exchange', coords: '3164, 3487, 0' },
  { name: 'mod_ash', combat: 75, modlevel: 'moderator', area: 'Falador', coords: '2964, 3378, 0' },
];
const stats = {};
for (const p of players) stats[p.name.toLowerCase()] = Object.fromEntries(SKILLS.map((s, i) => [s, s === 'Hitpoints' ? 10 + (i % 5) * 20 : 1 + ((i * 11 + p.combat) % 99)]));
const items = [[4151, 'Abyssal whip'], [4587, 'Dragon scimitar'], [1127, 'Rune platebody'], [995, 'Coins'], [385, 'Shark'], [5733, 'Rotten potato'], [11802, 'Armadyl godsword'], [1215, 'Dragon dagger']];
const accounts = [
  { id: 1, username: 'admin', modlevel: 'owner', online: true },
  { id: 2, username: 'umpix', modlevel: 'admin', online: true },
  { id: 3, username: 'skywarp45', modlevel: 'player', online: true },
  { id: 4, username: 'bean_one', modlevel: 'player', online: true },
  { id: 5, username: 'mod_ash', modlevel: 'moderator', online: true },
  { id: 6, username: 'old_alt', modlevel: 'player', online: false },
];
const teleports = [
  { name: 'Cities', destinations: [{ name: 'Lumbridge', x: 3222, z: 3218, level: 0 }, { name: 'Varrock', x: 3213, z: 3428, level: 0 }, { name: 'Falador', x: 2964, z: 3378, level: 0 }] },
  { name: 'Bosses', destinations: [{ name: 'Giant Mole', x: 3005, z: 3377, level: 0 }, { name: 'Barrows', x: 3565, z: 3289, level: 0 }] },
  { name: 'Skilling', destinations: [{ name: 'Draynor willows', x: 3086, z: 3235, level: 0 }, { name: 'Mining guild', x: 3046, z: 9756, level: 0 }] },
];
const audit = [];
log('admin','give','Umpix: Abyssal whip x1');
log('mod_ash','kick','bean_one');
log('Umpix','stats','self: all levels 99');
log('admin','broadcast','Server restart at 13:00');
const started = Date.now() - 3 * 86400e3 - 4 * 3600e3;

function log(actor, action, detail) {
  const t = new Date();
  audit.unshift({ time: t.toTimeString().slice(0, 8), actor, action, detail });
  audit.length = Math.min(audit.length, 200);
}

// Hub layout: the real bundled file when run from the repo, edits kept in memory only.
let hubSaved = null;
function hubLayout() {
  if (hubSaved) return hubSaved;
  const file = require('node:path').join(__dirname, '..', '..', '..', '..', 'content', 'other', 'beanlab-soulwars', 'src', 'main', 'resources', 'org', 'rsmod', 'content', 'other', 'beanlab', 'soulwars', 'hub-layout.json');
  return JSON.parse(require('node:fs').readFileSync(file, 'utf8'));
}

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

const server = http.createServer((req, res) => {
  if (req.headers.authorization !== `Bearer ${TOKEN}`) return send(res, 401, { error: 'Token rejected' });
  const url = new URL(req.url, 'http://x');
  let raw = '';
  req.on('data', (c) => (raw += c));
  req.on('end', () => {
    const body = raw ? JSON.parse(raw) : {};
    const path = url.pathname;
    const m = (re) => path.match(re);
    if (req.method === 'GET' && path === '/v1/me') return send(res, 200, ME);
    if (req.method === 'GET' && path === '/v1/players') return send(res, 200, { players });
    if (req.method === 'GET' && path === '/v1/world') return send(res, 200, { online: players.length, uptimeSeconds: Math.floor((Date.now() - started) / 1000), xpRate: 150 });
    if (req.method === 'GET' && path === '/v1/teleports') return send(res, 200, { categories: teleports });
    if (req.method === 'GET' && path === '/v1/audit') return send(res, 200, { entries: audit.slice(0, 80) });
    if (req.method === 'GET' && path === '/v1/items/search') {
      const q = (url.searchParams.get('q') || '').toLowerCase();
      return send(res, 200, { items: items.filter((i) => i[1].toLowerCase().includes(q)).map(([id, name]) => ({ id, name })) });
    }
    if (req.method === 'GET' && path === '/v1/accounts') {
      const q = (url.searchParams.get('q') || '').toLowerCase();
      const flip = (a) => (process.env.MOCK_FLIP && a.username === 'bean_one' ? Math.floor(Date.now() / 5000) % 2 === 0 : a.online);
      return send(res, 200, { accounts: accounts.filter((a) => a.username.includes(q)).map((a) => ({ ...a, online: flip(a) })) });
    }
    let r;
    if ((r = m(/^\/v1\/players\/([^/]+)$/)) && req.method === 'GET') {
      const name = decodeURIComponent(r[1]);
      const p = players.find((x) => x.name.toLowerCase() === name.toLowerCase());
      return p ? send(res, 200, { ...p, stats: stats[name.toLowerCase()] }) : send(res, 404, { error: `${name} isn't online` });
    }
    if ((r = m(/^\/v1\/players\/([^/]+)\/([a-z-]+)$/)) && req.method === 'POST') {
      const name = decodeURIComponent(r[1]);
      const action = r[2];
      if (action === 'stats') Object.assign(stats[name.toLowerCase()] || {}, body.levels || {});
      const detail = action === 'give' ? `${name}: item ${body.id} x${body.qty}` : name;
      log(ME.name, action, detail);
      return send(res, 200, { ok: true, message: `${action} done for ${name}` });
    }
    if (req.method === 'POST' && path === '/v1/teleport') { log(ME.name, 'teleport', `${body.target} -> ${body.x}, ${body.z}`); return send(res, 200, { ok: true, message: `Teleported ${body.target}` }); }
    if (req.method === 'POST' && path === '/v1/world/broadcast') { log(ME.name, 'broadcast', body.message); return send(res, 200, { ok: true, message: 'Broadcast sent' }); }
    if (req.method === 'POST' && path === '/v1/world/reboot') { log(ME.name, 'reboot', `in ${body.seconds}s`); return send(res, 200, { ok: true, message: `Reboot in ${body.seconds / 60} minute(s)` }); }
    if (req.method === 'POST' && path === '/v1/accounts') {
      accounts.unshift({ id: accounts.length + 1, username: body.username, modlevel: body.modlevel, online: false });
      log(ME.name, 'create-account', body.username);
      return send(res, 200, { ok: true, message: 'Created', password: 'Zk3mQp8WvT2nXc7R' });
    }
    if ((r = m(/^\/v1\/accounts\/(\d+)\/modlevel$/)) && req.method === 'POST') {
      const a = accounts.find((x) => x.id === Number(r[1]));
      if (a) a.modlevel = body.modlevel;
      log(ME.name, 'modlevel', `${a && a.username}: ${body.modlevel}`);
      return send(res, 200, { ok: true, message: `Mod level now ${body.modlevel}` });
    }
    if ((r = m(/^\/v1\/accounts\/(\d+)\/password$/)) && req.method === 'POST') { log(ME.name, 'reset-password', r[1]); return send(res, 200, { ok: true, message: 'Reset', password: 'Hb9sLd4NfY6uJe2A' }); }
    if ((r = m(/^\/v1\/accounts\/(\d+)$/)) && req.method === 'DELETE') {
      const i = accounts.findIndex((x) => x.id === Number(r[1]));
      if (i >= 0) { log(ME.name, 'delete-account', accounts[i].username); accounts.splice(i, 1); }
      return send(res, 200, { ok: true, message: 'Deleted' });
    }
    if (req.method === 'GET' && path === '/v1/hub/layout') {
      return send(res, 200, { source: hubSaved ? 'override' : 'bundled', layout: hubLayout(), floorsEditable: false, note: 'Walls, objects and NPCs apply after a server restart. Floors need a cache repack.' });
    }
    if (req.method === 'PUT' && path === '/v1/hub/layout') {
      const problems = [];
      if (body.version !== 1) problems.push('version must be 1');
      for (const [i, n] of (body.npcs || []).entries()) if (!n.npc) problems.push(`npcs[${i}]: npc is required`);
      for (const [i, l] of (body.locs || []).entries()) if (!l.loc) problems.push(`locs[${i}]: loc is required`);
      log(ME.name, 'hub-layout', `${raw.length} bytes${problems.length ? ' (refused)' : ''}`);
      if (problems.length) return send(res, 422, { error: `The layout has ${problems.length} problem(s); nothing was saved.`, problems });
      hubSaved = body;
      return send(res, 200, { ok: true, message: 'Saved. Restart the server to apply walls, objects and NPCs.', problems: [] });
    }
    return send(res, 404, { error: 'Not found' });
  });
});

const PORT = Number(process.env.MOCK_PORT) || 8099;
server.listen(PORT, '127.0.0.1', () => console.log(`mock admin API on http://127.0.0.1:${PORT} (token dev-token)`));
