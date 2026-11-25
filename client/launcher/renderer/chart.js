// Inline SVG area chart for progress history (no library).

function drawChart(svg, points, { value, color = "#5ec64c", format = String } = {}) {
  const box = svg.getBoundingClientRect();
  const w = Math.max(100, Math.round(box.width));
  const h = Math.max(60, Math.round(box.height));
  svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
  svg.innerHTML = "";
  const values = points.map(value).filter((v) => Number.isFinite(v));
  if (points.length < 2 || values.length < 2) return false;

  const pad = { l: 46, r: 8, t: 10, b: 20 };
  const t0 = Date.parse(points[0].t);
  const t1 = Date.parse(points[points.length - 1].t);
  let min = Math.min(...values);
  let max = Math.max(...values);
  if (max === min) {
    max += 1;
    min -= 1;
  }
  const x = (t) => pad.l + ((t - t0) / (t1 - t0 || 1)) * (w - pad.l - pad.r);
  const y = (v) => pad.t + (1 - (v - min) / (max - min)) * (h - pad.t - pad.b);
  const ns = "http://www.w3.org/2000/svg";
  const add = (tag, attrs, text) => {
    const node = document.createElementNS(ns, tag);
    for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
    if (text != null) node.textContent = text;
    svg.appendChild(node);
    return node;
  };

  for (let i = 0; i <= 3; i++) {
    const v = min + ((max - min) * i) / 3;
    const yy = y(v);
    add("line", { x1: pad.l, x2: w - pad.r, y1: yy, y2: yy, class: "grid" });
    add("text", { x: pad.l - 6, y: yy + 4, class: "axis", "text-anchor": "end" }, format(v));
  }
  const fmtDay = (t) => new Date(t).toLocaleDateString([], { month: "short", day: "numeric" });
  add("text", { x: pad.l, y: h - 4, class: "axis" }, fmtDay(t0));
  add("text", { x: w - pad.r, y: h - 4, class: "axis", "text-anchor": "end" }, fmtDay(t1));

  const pts = points
    .map((p) => [x(Date.parse(p.t)), value(p)])
    .filter(([, v]) => Number.isFinite(v))
    .map(([xx, v]) => [xx, y(v)]);
  const line = pts.map(([xx, yy], i) => `${i ? "L" : "M"}${xx.toFixed(1)} ${yy.toFixed(1)}`).join(" ");
  const id = `fill-${Math.random().toString(36).slice(2, 8)}`;
  const defs = add("defs", {});
  const grad = document.createElementNS(ns, "linearGradient");
  grad.setAttribute("id", id);
  grad.setAttribute("x1", "0");
  grad.setAttribute("y1", "0");
  grad.setAttribute("x2", "0");
  grad.setAttribute("y2", "1");
  grad.innerHTML = `<stop offset="0" stop-color="${color}" stop-opacity=".28"/><stop offset="1" stop-color="${color}" stop-opacity="0"/>`;
  defs.appendChild(grad);
  const base = h - pad.b;
  add("path", { d: `${line} L${pts[pts.length - 1][0]} ${base} L${pts[0][0]} ${base}Z`, fill: `url(#${id})` });
  add("path", { d: line, fill: "none", stroke: color, "stroke-width": 2 });
  const last = pts[pts.length - 1];
  add("circle", { cx: last[0], cy: last[1], r: 3.5, fill: color });
  return true;
}
