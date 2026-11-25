// Account feed on Home: level-ups, quests, diaries, combat tasks, collection log, milestones.

const FEED_KINDS = {
  level: { label: "Level up", glyph: "", cls: "k-level" },
  milestone: { label: "Milestone", glyph: "★", cls: "k-milestone" },
  quest: { label: "Quest", glyph: "Q", cls: "k-quest" },
  diary: { label: "Achievement diary", glyph: "D", cls: "k-diary" },
  combat: { label: "Combat task", glyph: "C", cls: "k-combat" },
  clog: { label: "Collection log", glyph: "L", cls: "k-clog" },
  pet: { label: "Pet", glyph: "P", cls: "k-pet" },
  clue: { label: "Treasure trail", glyph: "T", cls: "k-clue" },
  drop: { label: "Valuable drop", glyph: "$", cls: "k-drop" },
  run: { label: "Script run", glyph: "▶", cls: "k-run" },
};

function feedIcon(item, kind) {
  const box = el("span", `feed-ic ${kind.cls}`);
  if (item.skill) {
    const img = el("img");
    img.src = skillIcon(item.skill);
    img.alt = "";
    img.width = 18;
    img.height = 18;
    box.append(img);
  } else {
    box.append(el("span", "", kind.glyph || "•"));
  }
  return box;
}

function renderFeed(list, items, { showCharacter = false } = {}) {
  list.innerHTML = "";
  if (!items?.length) {
    const empty = el("li", "feed-empty");
    empty.append(
      el("b", "", "Nothing here yet"),
      el("span", "", "Level-ups, quests, diaries, combat tasks, collection log items and milestones show up here as you play with the Beanscape Companion plugin on.")
    );
    list.append(empty);
    return;
  }
  for (const item of items) {
    const kind = FEED_KINDS[item.type] || { label: item.type, glyph: "•", cls: "" };
    const li = el("li", "feed-item");
    const text = el("span", "feed-text");
    text.append(el("span", "feed-title", item.title || kind.label));
    const sub = [item.detail || kind.label];
    if (showCharacter && item.character) sub.unshift(item.character);
    text.append(el("span", "feed-sub", sub.join(" · ")));
    const when = el("time", "feed-when", fmtAgo(item.t));
    when.dateTime = item.t;
    when.title = new Date(item.t).toLocaleString();
    li.append(feedIcon(item, kind), text, when);
    list.append(li);
  }
}
