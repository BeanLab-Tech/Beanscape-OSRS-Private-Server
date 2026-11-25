// Session health: table on Home and Sessions, cards in the sidebar.

function healthClients(health) {
  return Array.isArray(health?.clients) ? health.clients : [];
}

function memoryLine(health) {
  const sys = health?.system;
  if (!sys) return "";
  const free = fmtMb(sys.availCommitMb);
  const room = sys.room == null ? "" : ` · room for about ${sys.room} more`;
  return `${free} free${room}`;
}

function statusDot(client) {
  return el("span", `dot ${client.script ? "d-run" : "d-idle"}`);
}

function renderHealthTable(container, health, { onRestart, onStop, compact = false } = {}) {
  container.innerHTML = "";
  const clients = healthClients(health);
  if (!clients.length) {
    container.append(el("p", "empty", "No clients running. Press Play to start one."));
    const sys = health?.system;
    if (sys?.room != null) container.append(el("p", "room", `This PC has room for about ${sys.room} clients right now.`));
    return;
  }
  const table = el("table", compact ? "ses compact" : "ses");
  const head = el("tr");
  const labels = compact ? ["", "Character", "Use", ""] : ["", "Character", "Script", "RAM", "CPU", "Up", ""];
  labels.forEach((label) => head.append(el("th", "", label)));
  table.append(head);
  for (const client of clients) {
    const row = el("tr");
    const dotCell = el("td");
    dotCell.append(statusDot(client));
    row.append(dotCell);
    if (compact) {
      const who = el("td", "name", client.name || "—");
      who.append(el("span", "sub2", client.script || "No script on"));
      const use = el("td", "", `${fmtMb(client.ramMb)} · ${client.cpu}%`);
      use.append(el("span", "sub2", `up ${fmtUptime(client.startedAt)}`));
      row.append(who, use);
    } else {
      row.append(el("td", "name", client.name || "—"));
      row.append(el("td", client.script ? "" : "muted", client.script || "No script on"));
      row.append(el("td", "", fmtMb(client.ramMb)));
      row.append(el("td", "", `${client.cpu}%`));
      row.append(el("td", "", fmtUptime(client.startedAt)));
    }
    const actions = el("td", "acts");
    const restart = el("button", "btn sm", "Restart");
    restart.type = "button";
    restart.setAttribute("aria-label", `Restart ${client.name}`);
    restart.addEventListener("click", () => onRestart?.(client.id, restart));
    const stop = el("button", "btn sm", "Stop");
    stop.type = "button";
    stop.setAttribute("aria-label", `Stop ${client.name}`);
    stop.addEventListener("click", () => onStop?.(client.id, stop));
    actions.append(restart, stop);
    row.append(actions);
    table.append(row);
  }
  container.append(table);
  const sys = health?.system;
  if (sys?.room != null) {
    const room = el("p", "room");
    room.append("Room for ");
    room.append(el("b", sys.room > 0 ? "ok" : "warn", `about ${sys.room} more`));
    room.append(" clients at the current memory setting.");
    container.append(room);
  }
}

function renderSideLive(container, health) {
  container.innerHTML = "";
  const clients = healthClients(health);
  if (!clients.length) {
    container.append(el("p", "side-empty", "None running"));
    return;
  }
  const maxRam = Math.max(1024, ...clients.map((c) => c.ramMb || 0));
  for (const client of clients) {
    const card = el("div", "live-card");
    const top = el("div", "live-top");
    top.append(statusDot(client), el("b", "", client.name || "Client"), el("span", "muted", fmtUptime(client.startedAt)));
    const bar = el("div", "bar");
    const fill = el("i");
    fill.style.width = `${Math.min(100, Math.round(((client.ramMb || 0) / maxRam) * 100))}%`;
    bar.append(fill);
    card.append(
      top,
      el("div", "live-script", client.script || "No script on"),
      bar,
      el("div", "live-meta", `${fmtMb(client.ramMb)} · CPU ${client.cpu}%`)
    );
    container.append(card);
  }
}
