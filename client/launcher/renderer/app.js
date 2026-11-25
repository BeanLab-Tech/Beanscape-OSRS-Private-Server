'use strict';

const $ = (id) => document.getElementById(id);
const api = window.beanscape;

let working = false;
let accountState = { activeId: null, accounts: [] };
let runtime = null;
let health = null;
let settingsState = { theme: 'gold', background: 'stone', memoryMb: 2048 };
let currentView = 'home';
let metric = 'xp';
let range = '30d';
let chartSkill = '';

// ---------- helpers ----------

function activeAccount() {
  return accountState.accounts.find((item) => item.id === accountState.activeId) || accountState.accounts[0] || null;
}

function isPlaying(id) {
  return Boolean(id) && (runtime?.runningIds || []).includes(id);
}

function initial(username) {
  const letter = String(username || '').trim().charAt(0);
  return letter ? letter.toUpperCase() : '?';
}

function showToast(message) {
  const toast = $('toast');
  if (!message) return;
  toast.hidden = false;
  toast.textContent = message;
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => {
    toast.hidden = true;
  }, 5200);
}

function setError(message) {
  $('error').textContent = message || '';
  if (message) showToast(message);
}

function addLog(line) {
  if (!line) return;
  const box = $('log');
  box.textContent += `${line}\n`;
  box.scrollTop = box.scrollHeight;
}

function segment(id, attr, onPick) {
  $(id).addEventListener('click', (event) => {
    const button = event.target.closest('button');
    if (!button) return;
    $(id).querySelectorAll('button').forEach((b) => b.classList.toggle('on', b === button));
    onPick(button.dataset[attr]);
  });
}

function cachedLine(snapshot) {
  if (!snapshot) return 'No stats yet. Play once with the Beanscape Companion plugin on (Play installs it).';
  const ago = fmtAgo(snapshot.liveAt);
  return ago ? `From the game · ${ago}` : 'From the game';
}

// ---------- views ----------

function showView(name) {
  currentView = name;
  document.querySelectorAll('.navi').forEach((button) => {
    const on = button.dataset.view === name;
    button.classList.toggle('on', on);
    if (on) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
  document.querySelectorAll('.view').forEach((section) => {
    section.hidden = section.dataset.view !== name;
  });
  if (name === 'home') {
    loadChart();
    loadFeed();
  }
  if (name === 'updates') loadUpdates();
}

document.querySelectorAll('.navi').forEach((button) => {
  button.addEventListener('click', () => showView(button.dataset.view));
});

// ---------- Home: character sheet ----------

function renderSkills(snapshot) {
  const list = $('skill-list');
  list.replaceChildren();
  ALL_SKILLS.forEach(([key, label]) => {
    const level = snapshot?.skills?.[key] ?? null;
    const row = el('div', 'sk');
    const img = el('img');
    img.src = skillIcon(key);
    img.alt = '';
    img.width = 18;
    img.height = 18;
    row.append(img, el('span', 'n', label), el('span', 'l', level == null ? '—' : String(level)));
    row.title = snapshot?.skillsXp?.[key] ? `${label}: ${fmtNumber(snapshot.skillsXp[key])} XP` : label;
    list.append(row);
  });
}

function renderHero() {
  const active = activeAccount();
  const snap = active?.snapshot || null;
  const pick = $('hero-pick');
  pick.replaceChildren();
  if (!accountState.accounts.length) {
    pick.append(new Option('No account yet', ''));
    pick.disabled = true;
  } else {
    pick.disabled = false;
    for (const item of accountState.accounts) {
      const option = new Option(item.username, item.id);
      option.selected = item.id === active.id;
      pick.append(option);
    }
  }
  $('hero-avatar').textContent = initial(active?.username);
  $('hero-badges').replaceChildren(...(active ? [accountBadges(active)] : []));
  $('hero-edit').hidden = !active;
  $('hero-add').hidden = Boolean(active);

  $('total-value').textContent = snap?.total ? fmtNumber(snap.total) : '—';
  $('combat-value').textContent = snap?.combat ? String(snap.combat) : '—';
  $('wealth-value').textContent = snap?.wealth?.total || snap?.wealth?.gp ? fmtGp(snap.wealth.total || snap.wealth.gp) : '—';
  $('playtime-value').textContent = snap ? playtimeLabel(snap) : '—';
  $('cached-at').textContent = active ? cachedLine(snap) : 'Add an account to start tracking stats.';
  renderSkills(snap);
  paintPlay();
}

function paintPlay() {
  const active = activeAccount();
  const live = isPlaying(active?.id);
  const button = $('play');
  button.classList.toggle('running', live);
  button.disabled = working || !runtime?.java;
  $('play-word').textContent = working ? 'WORKING' : live ? 'STOP' : 'PLAY';
  button.setAttribute('aria-label', live ? `Stop ${active?.username}` : active ? `Play ${active.username}` : 'Play');
  if (working) return;
  $('play-sub').textContent = live
    ? 'Playing now. Stop closes their client.'
    : active
      ? `Signs in as ${active.username}`
      : 'Add an account, or sign in on the client.';
}

$('hero-pick').addEventListener('change', () => choose($('hero-pick').value));

// ---------- Home: progress chart ----------

function fillSkillPick() {
  const pick = $('skill-pick');
  if (pick.options.length) return;
  pick.append(new Option('All skills', ''));
  ALL_SKILLS.forEach(([key, label]) => pick.append(new Option(label, key)));
  pick.addEventListener('change', () => {
    chartSkill = pick.value;
    loadChart();
  });
}

segment('metric-seg', 'metric', (value) => {
  metric = value;
  loadChart();
});
segment('range-seg', 'range', (value) => {
  range = value;
  loadChart();
});

let chartToken = 0;
async function loadChart() {
  fillSkillPick();
  const active = activeAccount();
  const svg = $('progress-chart');
  const empty = $('chart-empty');
  const delta = $('chart-delta');
  $('skill-pick').hidden = metric !== 'xp';
  const token = ++chartToken;
  const points = active ? await api.getHistory(active.id, range) : [];
  if (token !== chartToken) return;
  const value =
    metric === 'wealth'
      ? (p) => p.wealth
      : metric === 'levels'
        ? (p) => p.total
        : chartSkill
          ? (p) => p.skillsXp?.[chartSkill]
          : (p) => p.totalXp;
  const format = metric === 'wealth' ? fmtGp : metric === 'levels' ? (v) => String(Math.round(v)) : fmtXp;
  const color = metric === 'wealth' ? '#ffd56e' : metric === 'levels' ? '#6ea6e4' : '#5ec64c';
  const drawn = drawChart(svg, points, { value, color, format });
  empty.hidden = drawn;
  if (!drawn) {
    empty.textContent = active
      ? 'Not enough history yet. A point is saved each time new stats arrive.'
      : 'Add an account to see progress.';
    delta.textContent = '';
    return;
  }
  const vals = points.map(value).filter(Number.isFinite);
  const diff = vals[vals.length - 1] - vals[0];
  const sign = diff >= 0 ? '+' : '';
  const label =
    metric === 'levels'
      ? `${sign}${Math.round(diff)} levels`
      : `${sign}${format(diff)}${metric === 'xp' ? ' XP' : ' gp'}`;
  delta.textContent = `${range === 'all' ? 'All time' : `Last ${range.replace('d', ' days')}`} · ${label}`;
}

// ---------- Home: account feed ----------

let feedScope = 'one';
let feedToken = 0;
segment('feed-seg', 'scope', (value) => {
  feedScope = value;
  loadFeed();
});

async function loadFeed() {
  const active = activeAccount();
  const token = ++feedToken;
  const items = active || feedScope === 'all' ? await api.getFeed(active?.id, feedScope) : [];
  if (token !== feedToken) return;
  renderFeed($('feed-list'), items, { showCharacter: feedScope === 'all' });
}

// ---------- Characters ----------

function renderRoster() {
  const list = $('account-list');
  list.replaceChildren();
  if (!accountState.accounts.length) {
    list.append(el('li', 'empty', 'No accounts yet. Add the username and password from Discord registration.'));
    return;
  }
  for (const item of accountState.accounts) {
    const li = el('li', `char${item.id === accountState.activeId ? ' on' : ''}`);
    const main = el('button', 'char-main');
    main.type = 'button';
    const avatar = el('span', 'avatar', initial(item.username));
    avatar.setAttribute('aria-hidden', 'true');
    const text = el('span', 'char-text');
    const name = el('span', 'nm', item.username);
    if (item.running) name.append(el('span', 'dot d-run'));
    const snap = item.snapshot;
    const sub = el(
      'span',
      'sub',
      snap
        ? `Total ${snap.total ? fmtNumber(snap.total) : '—'} · Combat ${snap.combat || '—'} · ${
            snap.wealth?.total || snap.wealth?.gp ? fmtGp(snap.wealth.total || snap.wealth.gp) : '—'
          } gp`
        : item.hasPassword
          ? 'No stats yet'
          : 'Password missing',
    );
    text.append(name, accountBadges(item), sub);
    main.append(avatar, text);
    main.addEventListener('click', async () => {
      await choose(item.id);
      showView('home');
    });
    const edit = el('button', 'btn sm ghost', 'Edit');
    edit.type = 'button';
    edit.setAttribute('aria-label', `Edit ${item.username}`);
    edit.addEventListener('click', () => openEditor(item));
    li.append(main, edit);
    list.append(li);
  }
}

async function choose(id) {
  if (!id || id === accountState.activeId) return;
  const result = await api.selectAccount(id);
  if (!result.ok) {
    setError(result.error);
    return;
  }
  accountState = result.state;
  renderAll();
}

// ---------- Sessions / live clients ----------

async function stopClient(id, button) {
  if (button) button.disabled = true;
  const result = await api.stop(id);
  if (!result.ok) setError(result.error);
  await refresh();
}

async function restartClient(id, button) {
  if (button) button.disabled = true;
  const stopped = await api.stop(id);
  if (!stopped.ok) {
    setError(stopped.error);
    return;
  }
  // Wait for the process to exit before the same account launches again.
  for (let i = 0; i < 20; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 250));
    await refresh();
    if (!isPlaying(id)) break;
  }
  const result = await api.play(id);
  if (!result.ok) setError(result.error);
  await refresh();
}

function renderHealth() {
  const opts = { onRestart: restartClient, onStop: stopClient };
  renderHealthTable($('sessions-health'), health, opts);
  renderSideLive($('side-live'), health);
  const sys = health?.system;
  $('sessions-mem').textContent = sys
    ? `${fmtMb(sys.freeMb)} free${sys.room == null ? '' : ` · room for about ${sys.room} more`}`
    : '';
  const count = (health?.clients || []).length;
  const badge = $('nav-sessions');
  badge.hidden = !count;
  badge.textContent = String(count);
  const status = $('status-clients');
  status.textContent = count ? `${count} client${count === 1 ? '' : 's'} running` : 'No clients running';
  status.classList.toggle('live', count > 0);
  $('memory-hint').textContent =
    sys?.room == null ? 'Applies to clients you start next.' : `At this setting, this PC has room for about ${sys.room} more clients.`;
}

// ---------- Updates ----------

let updatesLoadedAt = 0;
async function loadUpdates(force = false) {
  if (!force && updatesData && Date.now() - updatesLoadedAt < 60000) {
    paintUpdates(updatesData);
    return;
  }
  updatesLoadedAt = Date.now();
  paintUpdates(await api.getUpdates());
}

function paintUpdates(data) {
  renderUpdates($('update-list'), $('update-detail'), data);
  const count = updateCount(data);
  const badge = $('nav-updates');
  badge.hidden = !count;
  badge.textContent = String(count);
  $('status-updates').textContent = count ? `${count} update${count === 1 ? '' : 's'} ready` : '';
}

$('updates-check').addEventListener('click', () => loadUpdates(true));

// ---------- Settings ----------

const THEMES = [
  { id: 'gold', name: 'Gold', color: '#e2b456' },
  { id: 'emerald', name: 'Emerald', color: '#5ec48a' },
  { id: 'crimson', name: 'Crimson', color: '#e0685a' },
  { id: 'azure', name: 'Azure', color: '#6ea6e4' },
  { id: 'amethyst', name: 'Amethyst', color: '#b48ae0' },
  { id: 'ember', name: 'Ember', color: '#ff9b33' },
  { id: 'bone', name: 'Bone', color: '#d8d0c0' },
];

const BACKGROUNDS = [
  { id: 'stone', name: 'Stone', color: '#2c241a', color2: '#0f0c09' },
  { id: 'ink', name: 'Ink', color: '#252527', color2: '#0b0b0c' },
  { id: 'midnight', name: 'Midnight', color: '#1e2940', color2: '#090c14' },
  { id: 'forest', name: 'Forest', color: '#213024', color2: '#0a0f0b' },
  { id: 'plum', name: 'Plum', color: '#302234', color2: '#0f0a10' },
  { id: 'slate', name: 'Slate', color: '#282e36', color2: '#0d0f12' },
];

function buildSwatches(boxId, items, key) {
  const box = $(boxId);
  for (const item of items) {
    const b = el('button', 'swatch');
    b.type = 'button';
    b.dataset.id = item.id;
    b.title = item.name;
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-label', item.name);
    b.style.setProperty('--swatch', item.color);
    if (item.color2) b.style.setProperty('--swatch-2', item.color2);
    b.addEventListener('click', async () => {
      settingsState = await api.saveSettings({ [key]: item.id });
      applySettings();
    });
    box.append(b);
  }
}

function paintSwatches(boxId, labelId, items, chosenId) {
  const chosen = items.find((i) => i.id === chosenId) || items[0];
  $(labelId).textContent = chosen.name;
  for (const b of $(boxId).children) b.setAttribute('aria-checked', String(b.dataset.id === chosen.id));
  return chosen;
}

/** Appearance applies from data attributes on <html>; the stylesheet owns the actual colors. */
function applySettings() {
  const theme = paintSwatches('theme-swatches', 'theme-label', THEMES, settingsState.theme);
  const bg = paintSwatches('bg-swatches', 'bg-label', BACKGROUNDS, settingsState.background);
  document.documentElement.dataset.theme = theme.id;
  document.documentElement.dataset.bg = bg.id;
  const memory = $('memory');
  memory.value = String(settingsState.memoryMb);
  $('memory-label').textContent = `${settingsState.memoryMb} MB`;
}

buildSwatches('theme-swatches', THEMES, 'theme');
buildSwatches('bg-swatches', BACKGROUNDS, 'background');

$('memory').addEventListener('input', () => {
  $('memory-label').textContent = `${$('memory').value} MB`;
});
$('memory').addEventListener('change', async () => {
  settingsState = await api.saveSettings({ memoryMb: Number($('memory').value) });
  applySettings();
});

function setDot(name, ok, text) {
  $(`dot-${name}`).className = `dot ${ok ? 'ok' : 'bad'}`;
  $(`val-${name}`).textContent = text;
}

function paintRuntime() {
  if (!runtime) return;
  const serverText = runtime.server ? `${runtime.serverName} reachable` : 'Not reachable. Is Tailscale connected?';
  const javaText = runtime.java ? `Java ${runtime.java.major}` : 'Java 17 or newer not found';
  const clientText = runtime.prepared
    ? `Microbot ${runtime.microbotVersion} ready`
    : `Microbot ${runtime.microbotVersion} sets up on first Play`;
  setDot('server', runtime.server, serverText);
  setDot('java', Boolean(runtime.java), javaText);
  setDot('client', runtime.prepared, clientText);
  $('java').hidden = Boolean(runtime.java);
  $('java-value').textContent = javaText;
  $('client-value').textContent = clientText;
  $('status-server').textContent = runtime.server ? 'Server up' : 'Server down';
  $('status-microbot').textContent = `Microbot ${runtime.microbotVersion}`;
}

// ---------- account editor ----------

function openEditor(account) {
  $('acct-id').value = account ? account.id : '';
  $('acct-user').value = account ? account.username : '';
  $('acct-pass').value = '';
  $('acct-pass').placeholder = account?.hasPassword ? 'Leave blank to keep' : 'Password';
  $('acct-pass').required = !account;
  $('editor-mode').textContent = account ? 'Edit' : 'Add';
  $('acct-error').textContent = '';
  const remove = $('acct-delete');
  remove.hidden = !account;
  remove.dataset.confirm = '';
  remove.textContent = 'Remove account';
  $('account-dialog').showModal();
  $('acct-user').focus();
}

function closeEditor() {
  $('account-dialog').close();
}

$('account-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  $('acct-error').textContent = '';
  const result = await api.saveAccount({
    id: $('acct-id').value,
    username: $('acct-user').value,
    password: $('acct-pass').value,
  });
  if (!result.ok) {
    $('acct-error').textContent = result.error;
    return;
  }
  accountState = result.state;
  renderAll();
  closeEditor();
});

$('acct-delete').addEventListener('click', async () => {
  const button = $('acct-delete');
  if (button.dataset.confirm !== 'yes') {
    button.dataset.confirm = 'yes';
    button.textContent = 'Remove this account';
    return;
  }
  const result = await api.deleteAccount($('acct-id').value);
  if (!result.ok) {
    $('acct-error').textContent = result.error;
    return;
  }
  accountState = result.state;
  renderAll();
  closeEditor();
});

$('acct-cancel').addEventListener('click', closeEditor);
$('add-account').addEventListener('click', () => openEditor(null));
$('hero-add').addEventListener('click', () => openEditor(null));
$('hero-edit').addEventListener('click', () => {
  const account = activeAccount();
  if (account) openEditor(account);
});

// ---------- admin tools ----------

async function refreshAdmin() {
  const status = await api.adminStatus();
  $('admin').hidden = !status.available;
  $('admin-state').textContent =
    status.installed && status.hasToken ? 'Installed' : status.installed ? 'Installed, no token' : 'Not installed';
  $('admin-remove').disabled = !status.installed;
}

$('admin-install').addEventListener('click', async () => {
  setError('');
  const result = await api.adminInstall($('admin-token').value);
  if (!result.ok) {
    setError(result.error);
    return;
  }
  $('admin-token').value = '';
  addLog('Admin tools installed. Start or restart the client to load them.');
  refreshAdmin();
  if (updatesData) loadUpdates(true);
});

$('admin-remove').addEventListener('click', async () => {
  const result = await api.adminRemove();
  if (!result.ok) setError(result.error);
  else addLog('Admin tools removed.');
  refreshAdmin();
  if (updatesData) loadUpdates(true);
});

// ---------- play / actions ----------

function setWorking(on) {
  working = on;
  $('progress-wrap').hidden = !on;
  $('progress-text').textContent = 'Getting ready';
  $('progress-pct').textContent = '';
  $('progress-bar').style.setProperty('--progress', '0');
  paintPlay();
}

async function run(action) {
  setError('');
  setWorking(true);
  const result = await action();
  setWorking(false);
  if (!result.ok) {
    setError(result.error);
    $('play-sub').textContent = result.error;
  }
  await refresh();
}

$('play').addEventListener('click', async () => {
  const active = activeAccount();
  if (isPlaying(active?.id)) {
    await stopClient(active.id, $('play'));
    return;
  }
  if (!active) {
    showToast('Add an account first');
    showView('characters');
    return;
  }
  run(() => api.play(active.id));
});
$('java').addEventListener('click', () => run(api.installJava));
$('rebuild').addEventListener('click', () => run(api.rebuild));
$('register').addEventListener('click', () => api.openRegister());
$('data').addEventListener('click', () => api.openData());

// ---------- render / refresh ----------

function renderAll() {
  renderHero();
  renderRoster();
  paintRuntime();
  if (currentView === 'home') {
    loadChart();
    loadFeed();
  }
}

async function refresh() {
  [runtime, accountState] = await Promise.all([api.status(), api.accounts()]);
  renderAll();
}

function tick() {
  const now = new Date();
  const clock = $('clock');
  clock.dateTime = now.toISOString();
  clock.textContent = now.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

api.onLog(addLog);
api.onProgress((fraction) => {
  if (fraction == null) return;
  const percent = Math.round(fraction * 100);
  $('progress-wrap').hidden = false;
  $('progress-text').textContent = 'Preparing the client';
  $('progress-pct').textContent = `${percent}%`;
  $('progress-bar').style.setProperty('--progress', String(fraction));
});
api.onState(() => refresh());
api.onHealth((next) => {
  health = next;
  renderHealth();
});

tick();
setInterval(tick, 15000);
setInterval(() => renderSideLive($('side-live'), health), 30000);
window.addEventListener('resize', () => {
  if (currentView === 'home') loadChart();
});

(async () => {
  settingsState = await api.getSettings();
  applySettings();
  health = await api.getHealth();
  renderHealth();
  await refresh();
  refreshAdmin();
  loadUpdates(true);
  const view = new URLSearchParams(location.search).get('view');
  if (view) showView(view);
})();
