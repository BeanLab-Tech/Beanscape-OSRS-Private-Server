'use strict';

// Registry of running Microbot clients plus a RAM/CPU sampler for the Live clients bar.

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const SYSTEM_MARGIN_MB = 1024;
// Plugins that are not "the script" a client is running.
const IGNORED = new Set(['CompanionPlugin', 'BeanscapeAdminPlugin', 'AgentServerPlugin']);

const clients = new Map();

function live() {
  for (const [id, rec] of clients) {
    if (!rec.proc || rec.proc.exitCode !== null) clients.delete(id);
  }
  return clients;
}

function add({ id, name, proc }) {
  clients.set(id, { id, name, proc, pid: proc.pid, startedAt: Date.now() });
}

function remove(id) {
  clients.delete(id);
}

function ids() {
  return [...live().keys()];
}

function has(id) {
  return live().has(id);
}

function list() {
  return [...live().values()].map(({ id, name, pid, startedAt }) => ({ id, name, pid, startedAt }));
}

function stop(id) {
  const rec = live().get(id);
  if (!rec) return false;
  try {
    rec.proc.kill();
  } catch {
    // already gone
  }
  return true;
}

function cpuPercent(prevSeconds, nextSeconds, elapsedMs, cores) {
  if (prevSeconds == null || nextSeconds == null || !(elapsedMs > 0)) return 0;
  const used = nextSeconds - prevSeconds;
  if (!(used > 0)) return 0;
  return Math.min(100, Math.round((used / (elapsedMs / 1000) / Math.max(1, cores)) * 100));
}

/** Script plugins the client has on, from Microbot's session file. Null when none. */
function scriptFromRecovery(dir, pid) {
  let raw;
  try {
    raw = JSON.parse(fs.readFileSync(path.join(dir, `session-${pid}.json`), 'utf8'));
  } catch {
    return null;
  }
  const names = (raw.activePlugins || [])
    .filter((item) => item?.plugin && !IGNORED.has(item.plugin) && !/^Microbot/.test(item.plugin))
    .map((item) => {
      const base = item.plugin.replace(/Plugin$/, '');
      return item.version ? `${base} ${item.version}` : base;
    });
  return names.length ? names.join(', ') : null;
}

/** How many more clients fit, given free memory and the per-client heap. */
function roomForClients(freeMb, heapMb) {
  return Math.max(0, Math.floor((freeMb - SYSTEM_MARGIN_MB) / (heapMb + 256)));
}

let native = null;
function loadNative() {
  if (native) return native;
  try {
    const koffi = require('koffi');
    const kernel32 = koffi.load('kernel32.dll');
    const COUNTERS = koffi.struct('BS_PROCESS_MEMORY_COUNTERS', {
      cb: 'uint32',
      PageFaultCount: 'uint32',
      PeakWorkingSetSize: 'size_t',
      WorkingSetSize: 'size_t',
      QuotaPeakPagedPoolUsage: 'size_t',
      QuotaPagedPoolUsage: 'size_t',
      QuotaPeakNonPagedPoolUsage: 'size_t',
      QuotaNonPagedPoolUsage: 'size_t',
      PagefileUsage: 'size_t',
      PeakPagefileUsage: 'size_t',
    });
    const OpenProcess = kernel32.func('void * __stdcall OpenProcess(uint32 access, bool inherit, uint32 pid)');
    const CloseHandle = kernel32.func('bool __stdcall CloseHandle(void *h)');
    const GetProcessTimes = kernel32.func(
      'bool __stdcall GetProcessTimes(void *h, _Out_ uint64 *create, _Out_ uint64 *exit, _Out_ uint64 *kernel, _Out_ uint64 *user)',
    );
    const GetMemoryInfo = kernel32.func(
      'bool __stdcall K32GetProcessMemoryInfo(void *h, _Inout_ BS_PROCESS_MEMORY_COUNTERS *c, uint32 cb)',
    );
    const size = koffi.sizeof(COUNTERS);
    native = (pid) => {
      const h = OpenProcess(0x1000, false, pid >>> 0);
      if (!h) return null;
      try {
        const c = { cb: size };
        const create = [0];
        const exit = [0];
        const kernel = [0];
        const user = [0];
        if (!GetMemoryInfo(h, c, size) || !GetProcessTimes(h, create, exit, kernel, user)) return null;
        return {
          ramMb: Number(c.WorkingSetSize) / (1024 * 1024),
          cpuSeconds: (Number(kernel[0]) + Number(user[0])) / 1e7,
        };
      } finally {
        CloseHandle(h);
      }
    };
  } catch {
    native = () => null;
  }
  return native;
}

function createSampler({ getHeapMb, recoveryDir, onSample, intervalMs = 2000 }) {
  let timer = null;
  let prevCpu = {};
  let prevAt = 0;
  const cores = os.cpus().length || 1;

  function tick() {
    const running = list();
    const now = Date.now();
    const read = loadNative();
    const out = [];
    const nextCpu = {};
    for (const client of running) {
      const reading = client.pid ? read(client.pid) : null;
      if (!reading) continue;
      nextCpu[client.pid] = reading.cpuSeconds;
      out.push({
        id: client.id,
        name: client.name,
        pid: client.pid,
        ramMb: Math.round(reading.ramMb),
        cpu: cpuPercent(prevCpu[client.pid], reading.cpuSeconds, prevAt ? now - prevAt : 0, cores),
        startedAt: client.startedAt,
        script: scriptFromRecovery(recoveryDir, client.pid),
      });
    }
    prevCpu = nextCpu;
    prevAt = now;
    const freeMb = Math.round(os.freemem() / (1024 * 1024));
    onSample({
      at: now,
      clients: out,
      system: { freeMb, totalMb: Math.round(os.totalmem() / (1024 * 1024)), room: roomForClients(freeMb, getHeapMb()) },
    });
    if (!running.length) stop();
  }

  function start() {
    if (timer) return;
    prevCpu = {};
    prevAt = 0;
    tick();
    timer = setInterval(tick, intervalMs);
  }

  function stop() {
    if (timer) clearInterval(timer);
    timer = null;
  }

  return { start, stop, tick, running: () => Boolean(timer) };
}

module.exports = { add, remove, ids, has, list, stop, cpuPercent, scriptFromRecovery, roomForClients, createSampler };
