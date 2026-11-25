// Shared display helpers (plain script, globals).

const ALL_SKILLS = [
  ["attack", "Attack"],
  ["hitpoints", "Hitpoints"],
  ["mining", "Mining"],
  ["strength", "Strength"],
  ["agility", "Agility"],
  ["smithing", "Smithing"],
  ["defence", "Defence"],
  ["herblore", "Herblore"],
  ["fishing", "Fishing"],
  ["ranged", "Ranged"],
  ["thieving", "Thieving"],
  ["cooking", "Cooking"],
  ["prayer", "Prayer"],
  ["crafting", "Crafting"],
  ["firemaking", "Firemaking"],
  ["magic", "Magic"],
  ["fletching", "Fletching"],
  ["woodcutting", "Woodcutting"],
  ["runecraft", "Runecraft"],
  ["slayer", "Slayer"],
  ["farming", "Farming"],
  ["construction", "Construction"],
  ["hunter", "Hunter"],
];

function skillIcon(key) {
  const name = key.charAt(0).toUpperCase() + key.slice(1);
  return `skills/${name}_icon.png`;
}

const ACCOUNT_TYPES = {
  NORMAL: { label: "Main", cls: "b-main" },
  IRONMAN: { label: "Iron", cls: "b-iron" },
  HARDCORE_IRONMAN: { label: "HC Iron", cls: "b-hc" },
  ULTIMATE_IRONMAN: { label: "UIM", cls: "b-uim" },
  GROUP_IRONMAN: { label: "GIM", cls: "b-iron" },
  HARDCORE_GROUP_IRONMAN: { label: "HC GIM", cls: "b-hc" },
  UNRANKED_GROUP_IRONMAN: { label: "GIM", cls: "b-iron" },
};

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text != null) node.textContent = text;
  return node;
}

function accountBadges(account) {
  const wrap = el("span", "badges");
  const snap = account?.snapshot || {};
  const type = ACCOUNT_TYPES[snap.accountType];
  if (type) wrap.append(el("span", `badge ${type.cls}`, type.label));
  else {
    const unknown = el("span", "badge b-unknown", "Type ?");
    unknown.title = "Log in once with the Beanscape Companion plugin on to detect this.";
    wrap.append(unknown);
  }
  if (typeof snap.members === "boolean") wrap.append(el("span", `badge ${snap.members ? "b-p2p" : "b-f2p"}`, snap.members ? "P2P" : "F2P"));
  return wrap;
}

function fmtNumber(n) {
  return Number(n || 0).toLocaleString("en-US");
}

function fmtGp(n) {
  const v = Number(n) || 0;
  const abs = Math.abs(v);
  if (abs >= 1e9) return `${(v / 1e9).toFixed(2)}B`;
  if (abs >= 1e7) return `${(v / 1e6).toFixed(1)}M`;
  if (abs >= 1e6) return `${(v / 1e6).toFixed(2)}M`;
  if (abs >= 1e4) return `${Math.round(v / 1e3)}K`;
  return String(Math.round(v));
}

function fmtXp(n) {
  const v = Number(n) || 0;
  if (Math.abs(v) >= 1e6) return `${(v / 1e6).toFixed(2)}M`;
  if (Math.abs(v) >= 1e4) return `${Math.round(v / 1e3)}K`;
  return fmtNumber(v);
}

function fmtMinutes(minutes) {
  const m = Math.max(0, Math.round(Number(minutes) || 0));
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${h}h ${m % 60}m`;
  return `${Math.floor(h / 24)}d ${h % 24}h`;
}

function fmtUptime(startedAt) {
  if (!startedAt) return "—";
  return fmtMinutes((Date.now() - startedAt) / 60000);
}

function fmtAgo(iso) {
  const t = Date.parse(iso || "");
  if (!Number.isFinite(t)) return "";
  const mins = Math.round((Date.now() - t) / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const h = Math.round(mins / 60);
  if (h < 48) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}

function fmtMb(mb) {
  const v = Number(mb) || 0;
  return v >= 1024 ? `${(v / 1024).toFixed(1)} GB` : `${Math.round(v)} MB`;
}

function playtimeLabel(snapshot) {
  return snapshot?.playtimeMinutes ? fmtMinutes(snapshot.playtimeMinutes) : "—";
}
