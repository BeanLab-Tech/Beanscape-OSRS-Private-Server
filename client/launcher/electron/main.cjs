'use strict';

const { app, BrowserWindow, ipcMain, shell, safeStorage } = require('electron');
const path = require('node:path');
const serverConfig = require('./server-config.cjs');
const { tcpReachable } = require('./download.cjs');
const { findJava, installJava } = require('./java.cjs');
const client = require('./client.cjs');
const admin = require('./admin.cjs');
const accounts = require('./accounts.cjs').bind(safeStorage);
const snapshots = require('./snapshots.cjs');
const history = require('./history.cjs');
const feed = require('./feed.cjs');
const sessions = require('./sessions.cjs');
const settings = require('./settings.cjs');
const updates = require('./updates.cjs');

const pluginsDir = path.join(__dirname, '..', 'plugins');
const adminJar = () => path.join(pluginsDir, admin.JAR_NAME);
const changelogFile = [
  path.join(__dirname, '..', '..', '..', 'CHANGELOG.md'), // repo checkout: client/launcher/electron -> repo root
  path.join(__dirname, '..', '..', 'CHANGELOG.md'), // workspace layout
].find((p) => require('fs').existsSync(p)) || path.join(__dirname, '..', '..', 'CHANGELOG.md');

let win = null;
let busy = false;

const dataDir = () => app.getPath('userData');
// The server chosen in Settings (live or staging). Read per call so a switch applies to the next Play.
const cfg = () => serverConfig.forServer(settings.read(dataDir()).server);
// Live keeps the original layout; other servers get their own client, home and history, because
// each runs a different Microbot/RuneLite build. Accounts and settings stay shared.
const serverDir = () => {
  const id = cfg().id;
  return id === serverConfig.DEFAULT_SERVER ? dataDir() : path.join(dataDir(), 'servers', id);
};
const homeDir = () => client.paths(serverDir(), cfg()).home;
const historyDir = () => path.join(serverDir(), 'history');
const pluginDir = () => path.join(homeDir(), '.runelite', 'microbot-plugins');

const send = (channel, payload) => {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
};
const log = (line) => send('log', String(line).trim());

let health = { at: 0, clients: [], system: null };
const sampler = sessions.createSampler({
  getHeapMb: () => settings.read(dataDir()).memoryMb,
  recoveryDir: path.join(homeDir(), '.runelite', 'microbot-recovery'),
  onSample: (sample) => {
    health = sample;
    send('health', health);
  },
});

/** Each launcher account joined with what the Companion plugin last wrote for that character. */
function accountsState() {
  const pack = accounts.publicState(dataDir());
  const all = snapshots.readAll(snapshots.companionDir(serverDir()));
  const running = new Set(sessions.ids());
  return {
    activeId: pack.activeId,
    accounts: pack.accounts.map((item) => ({
      ...item,
      snapshot: snapshots.forAccount(all, item),
      running: running.has(item.id),
    })),
  };
}

/** Appends a history point for any character whose snapshot changed. Cheap enough to run on a timer. */
function recordHistory() {
  const pack = accountsState();
  let changed = false;
  for (const item of pack.accounts) {
    if (!item.snapshot) continue;
    const point = history.pointFromSnapshot(item.snapshot);
    if (history.record(historyDir(), item.username, point)) changed = true;
  }
  return changed;
}

async function status() {
  const [server, java] = await Promise.all([
    tcpReachable(cfg().gameHost, cfg().gamePort),
    findJava(cfg().minJava),
  ]);
  return {
    serverId: cfg().id,
    serverName: cfg().name,
    server,
    java: java ? { major: java.major } : null,
    prepared: client.isPrepared(serverDir(), cfg()),
    running: sessions.ids().length > 0,
    runningIds: sessions.ids(),
    microbotVersion: cfg().microbot.version,
    settings: settings.read(dataDir()),
    busy,
  };
}

function updatesState() {
  return updates.build({
    changelogFile,
    pluginsDir,
    installedDir: pluginDir(),
    launcherVersion: app.getVersion(),
    microbotVersion: cfg().microbot.version,
    adminInstalled: admin.status(serverDir(), adminJar()).installed,
  });
}

function watchClient(account, child) {
  sessions.add({ id: account.id, name: account.username, proc: child });
  sampler.start();
  const gone = () => {
    sessions.remove(account.id);
    send('state', {});
  };
  child.on('exit', gone);
  child.on('error', (err) => {
    log(`Launch failed: ${err.message}`);
    gone();
  });
  send('state', {});
}

async function play(_event, requestedId) {
  if (busy) return { ok: false, error: 'Already working' };
  busy = true;
  try {
    const pack = accounts.publicState(dataDir());
    const account = pack.accounts.find((item) => item.id === (requestedId || pack.activeId)) || null;
    if (account && sessions.has(account.id)) return { ok: false, error: `${account.username} is already playing` };
    const java = await findJava(cfg().minJava);
    if (!java) return { ok: false, error: `Java ${cfg().minJava}+ not found. Use "Install Java" first.` };
    if (!client.isPrepared(serverDir(), cfg())) {
      await client.prepare(serverDir(), cfg(), log, (fraction) => send('progress', fraction));
      send('progress', null);
    }
    if (updates.ensureCompanion(pluginsDir, pluginDir())) log('Installed the Beanscape Companion plugin');
    if (!(await tcpReachable(cfg().gameHost, cfg().gamePort))) {
      log('Warning: game server is not reachable. Is Tailscale connected?');
    }
    if (account) accounts.select(dataDir(), account.id);
    const launchArgs = accounts.prepareLaunch(dataDir(), homeDir());
    const child = client.launch(serverDir(), cfg(), java.javaw, launchArgs, settings.read(dataDir()).memoryMb);
    watchClient(account || { id: `anon-${child.pid}`, username: 'Client' }, child);
    log(account ? `Started ${account.username}` : 'Microbot started');
    return { ok: true };
  } catch (err) {
    send('progress', null);
    return { ok: false, error: err.message };
  } finally {
    busy = false;
  }
}

async function installJavaFlow() {
  if (busy) return { ok: false, error: 'Already working' };
  busy = true;
  try {
    log(`Installing ${cfg().javaInstallId} with winget`);
    const code = await installJava(cfg().javaInstallId, log);
    return code === 0 ? { ok: true } : { ok: false, error: `winget exited with code ${code}` };
  } catch (err) {
    return { ok: false, error: `winget unavailable: ${err.message}` };
  } finally {
    busy = false;
  }
}

function createWindow() {
  win = new BrowserWindow({
    width: 1180,
    height: 760,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#0f0c09',
    title: 'Beanscape',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });
  app.whenReady().then(() => {
    const accountCall = (fn) => {
      try {
        return { ok: true, state: fn() };
      } catch (err) {
        return { ok: false, error: err.message };
      }
    };

    ipcMain.handle('status', status);
    ipcMain.handle('play', play);
    ipcMain.handle('stop', (_event, id) => {
      const ok = sessions.stop(id);
      return { ok, error: ok ? undefined : 'That client is not running' };
    });
    ipcMain.handle('install-java', installJavaFlow);
    ipcMain.handle('rebuild', async () => {
      if (busy || sessions.ids().length) return { ok: false, error: 'Close the clients first' };
      busy = true;
      try {
        await client.prepare(serverDir(), cfg(), log, (f) => send('progress', f));
        send('progress', null);
        return { ok: true };
      } catch (err) {
        send('progress', null);
        return { ok: false, error: err.message };
      } finally {
        busy = false;
      }
    });
    ipcMain.handle('admin-status', () => admin.status(serverDir(), adminJar()));
    ipcMain.handle('admin-install', (_event, token) => {
      if (sessions.ids().length) return { ok: false, error: 'Close the clients first so they can load the plugin.' };
      try {
        admin.install(serverDir(), adminJar(), cfg().adminApiUrl, token);
        return { ok: true };
      } catch (err) {
        return { ok: false, error: err.message };
      }
    });
    ipcMain.handle('admin-remove', () => {
      if (sessions.ids().length) return { ok: false, error: 'Close the clients first.' };
      admin.remove(serverDir());
      return { ok: true };
    });
    ipcMain.handle('open-register', () => shell.openExternal(cfg().registerUrl));
    ipcMain.handle('open-data', () => shell.openPath(dataDir()));

    ipcMain.handle('accounts-state', () => {
      recordHistory();
      return accountsState();
    });
    ipcMain.handle('accounts-save', (_event, payload) => {
      const result = accountCall(() => accounts.save(dataDir(), payload || {}));
      return result.ok ? { ok: true, state: accountsState() } : result;
    });
    ipcMain.handle('accounts-select', (_event, id) => {
      const result = accountCall(() => accounts.select(dataDir(), id));
      return result.ok ? { ok: true, state: accountsState() } : result;
    });
    ipcMain.handle('accounts-delete', (_event, id) => {
      const result = accountCall(() => accounts.remove(dataDir(), id));
      return result.ok ? { ok: true, state: accountsState() } : result;
    });

    ipcMain.handle('history', (_event, id, range) => {
      const account = accounts.publicState(dataDir()).accounts.find((item) => item.id === id);
      return account ? history.query(historyDir(), account.username, range) : [];
    });
    ipcMain.handle('feed', (_event, id, scope) => {
      const pack = accounts.publicState(dataDir());
      const chosen = scope === 'all' ? pack.accounts : pack.accounts.filter((item) => item.id === id);
      const items = [];
      for (const account of chosen) {
        const events = feed.readEvents(path.join(snapshots.companionDir(serverDir()), 'events'), account.username);
        const points = history.query(historyDir(), account.username, 'all');
        for (const item of feed.buildFeed({ events, points, limit: 50 })) {
          items.push({ ...item, character: account.username });
        }
      }
      return items.sort((a, b) => Date.parse(b.t) - Date.parse(a.t)).slice(0, 50);
    });

    ipcMain.handle('health', () => health);
    ipcMain.handle('updates', () => updatesState());
    ipcMain.handle('settings-get', () => settings.read(dataDir()));
    ipcMain.handle('settings-save', (_event, patch) => settings.save(dataDir(), patch || {}));

    createWindow();
    recordHistory();
    // New plugin data arrives while a client runs; tell the renderer so the home page stays live.
    setInterval(() => {
      if (sessions.ids().length && recordHistory()) send('state', {});
    }, 15000);
  });
  app.on('window-all-closed', () => app.quit());
}
