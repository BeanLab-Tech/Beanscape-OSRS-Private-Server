'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const { download } = require('./download.cjs');
const { patchJar, sha256File } = require('./patcher.cjs');

function paths(dataDir, cfg) {
  const v = cfg.microbot.version;
  return {
    original: path.join(dataDir, 'original', `microbot-${v}.jar`),
    patched: path.join(dataDir, `microbot-${v}-beanlab.jar`),
    stamp: path.join(dataDir, 'patched.json'),
    home: path.join(dataDir, 'home'),
  };
}

function modulusId(cfg) {
  return crypto.createHash('sha256').update(cfg.modulus).digest('hex').slice(0, 16);
}

/** The patched jar is current when it exists and was built for this modulus and Microbot jar. */
function isPrepared(dataDir, cfg) {
  const p = paths(dataDir, cfg);
  if (!fs.existsSync(p.patched)) return false;
  try {
    const stamp = JSON.parse(fs.readFileSync(p.stamp, 'utf8'));
    return stamp.modulus === modulusId(cfg) && stamp.sha256 === cfg.microbot.sha256;
  } catch {
    return false;
  }
}

/** Downloads (if needed) and verifies Microbot, then patches it for the server. */
async function prepare(dataDir, cfg, log, onProgress) {
  const p = paths(dataDir, cfg);
  fs.mkdirSync(path.dirname(p.original), { recursive: true });
  fs.mkdirSync(p.home, { recursive: true });

  const have = fs.existsSync(p.original) && sha256File(p.original) === cfg.microbot.sha256;
  if (!have) {
    log(`Downloading Microbot ${cfg.microbot.version}`);
    await download(cfg.microbot.url, p.original, onProgress);
    if (sha256File(p.original) !== cfg.microbot.sha256) {
      fs.rmSync(p.original, { force: true });
      throw new Error('Downloaded jar failed the SHA-256 check, so it was deleted');
    }
  }
  log('Verified Microbot jar, patching for the BeanLab server');
  patchJar(p.original, p.patched, cfg.modulus, cfg.microbot.sha256);
  fs.writeFileSync(p.stamp, JSON.stringify({ modulus: modulusId(cfg), sha256: cfg.microbot.sha256 }));
  log('Client ready');
}

/** Starts Microbot with an isolated home so the normal RuneLite install is never touched. */
function launch(dataDir, cfg, javaw, extraArgs = [], heapMb = cfg.heapMb) {
  const p = paths(dataDir, cfg);
  fs.mkdirSync(p.home, { recursive: true });
  const args = [
    `-Xmx${heapMb}m`,
    `-Duser.home=${p.home}`,
    `-Drunelite.http-service.url=${cfg.httpServiceUrl}`,
    '-jar',
    p.patched,
    `--jav_config=${cfg.javConfigUrl}`,
    '--disable-telemetry',
    ...extraArgs,
  ];
  const child = spawn(javaw, args, { cwd: dataDir, detached: true, stdio: 'ignore' });
  child.unref();
  return child;
}

module.exports = { paths, isPrepared, prepare, launch, modulusId };
