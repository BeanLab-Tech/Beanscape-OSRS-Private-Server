// Updates tab: the Beanscape changelog plus the state of each piece that ships with the launcher.

let updatesData = null;
let updateSelected = "changes";

const PLUGIN_STATE = {
  "up-to-date": "up to date",
  update: "update ready",
  "not-installed": "not installed",
  optional: "optional · needs a token",
  missing: "not bundled in this build",
};

function updateCount(data = updatesData) {
  if (!data) return 0;
  return (data.plugins || []).filter((p) => p.state === "update" || p.state === "not-installed").length;
}

function changeCount(data) {
  return (data?.changelog || []).reduce((n, day) => n + day.entries.length, 0);
}

function pluginLine(plugin) {
  const version = plugin.version ? `v${plugin.version} · ` : "";
  return `${version}${PLUGIN_STATE[plugin.state] || plugin.state}`;
}

/** Renders `text` with `backtick` spans as <code>, never as HTML. */
function richText(tag, text) {
  const node = el(tag);
  text.split(/(`[^`]+`)/).forEach((part) => {
    if (part.startsWith("`") && part.endsWith("`") && part.length > 2) node.append(el("code", "", part.slice(1, -1)));
    else if (part) node.append(document.createTextNode(part));
  });
  return node;
}

function renderChangelog(body, data) {
  if (!data.changelog.length) {
    body.append(el("p", "muted", "No entries yet. Add them to CHANGELOG.md in the Beanscape folder."));
    return;
  }
  for (const day of data.changelog) {
    body.append(el("p", "changelog-day", day.date));
    for (const entry of day.entries) {
      const block = el("div", "changelog-entry");
      block.append(el("h4", "", entry.title));
      entry.paragraphs.forEach((text) => block.append(richText("p", text)));
      body.append(block);
    }
  }
}

function renderUpdates(list, detail, data, { onRefresh } = {}) {
  updatesData = data;
  list.innerHTML = "";
  detail.innerHTML = "";
  if (!data) {
    list.append(el("li", "empty", "Checking…"));
    return;
  }
  const items = [
    { key: "changes", name: "What changed", line: `${changeCount(data)} fixes and features`, mark: "★", state: "up-to-date" },
    { key: "launcher", name: "Launcher", line: `v${data.launcher.version}`, mark: "B", state: "up-to-date" },
    ...data.plugins.map((p) => ({ key: p.jar, name: p.name, line: pluginLine(p), mark: p.jar.startsWith("BeanscapeCompanion") ? "C" : "A", state: p.state, plugin: p })),
    { key: "microbot", name: "Microbot client", line: `${data.microbot.version} · pinned to the server`, mark: "M", state: "up-to-date" },
  ];
  if (!items.some((i) => i.key === updateSelected)) updateSelected = items[0].key;

  for (const item of items) {
    const li = el("li");
    const button = el("button", `upd${item.key === updateSelected ? " on" : ""}`);
    button.type = "button";
    const text = el("span", "upd-text");
    const title = el("span", "t", item.name);
    if (item.state === "update" || item.state === "not-installed") title.append(el("span", "new", " NEW"));
    text.append(title, el("span", "v", item.line));
    const icon = el("span", "ic");
    icon.append(el("span", "ic-text gold", item.mark));
    button.append(icon, text);
    button.addEventListener("click", () => {
      updateSelected = item.key;
      renderUpdates(list, detail, updatesData, { onRefresh });
    });
    li.append(button);
    list.append(li);
  }

  const chosen = items.find((i) => i.key === updateSelected);
  const head = el("div", "ph");
  head.append(el("h3", "", chosen.key === "changes" ? "What changed" : chosen.name));
  detail.append(head);
  const body = el("div", "log");
  if (chosen.key === "changes") {
    renderChangelog(body, data);
  } else if (chosen.key === "launcher") {
    body.append(
      el("p", "", `Beanscape launcher v${data.launcher.version}. It starts the patched Microbot client, keeps your accounts, and shows your characters.`),
      el("p", "muted", "Launcher changes are listed under What changed.")
    );
  } else if (chosen.key === "microbot") {
    body.append(
      el("p", "", `Microbot ${data.microbot.version}, patched for the BeanLab server. Play prepares it on first launch.`),
      el("p", "muted", "The server is pinned to a fixed game revision, so newer Microbot releases cannot log in. This build stays on this version on purpose.")
    );
  } else {
    const p = chosen.plugin;
    const about = p.jar.startsWith("BeanscapeCompanion")
      ? "Reads your character in the game client (skills, XP, wealth, account type, playtime) and writes it for the Home page. Read-only. Play installs and updates it for you."
      : "The in-game admin console for server owners. Install it from Settings with an admin token.";
    body.append(el("p", "", about), el("p", "muted", pluginLine(p)));
    if (p.state === "update" || p.state === "not-installed") {
      body.append(el("p", "muted", "Press Play to install this. The client loads it on start."));
    }
  }
  detail.append(body);
}
