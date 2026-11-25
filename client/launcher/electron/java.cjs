'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { execFile, spawn } = require('node:child_process');

/** Parses the major version out of `java -version` output (`"21.0.3"`, `"1.8.0_402"`). */
function parseJavaMajor(text) {
  const m = /version "(\d+)(?:\.(\d+))?/.exec(text || '');
  if (!m) return 0;
  const first = Number(m[1]);
  return first === 1 ? Number(m[2] || 0) : first;
}

function candidates(env = process.env) {
  const list = [];
  if (env.JAVA_HOME) list.push(path.join(env.JAVA_HOME, 'bin', 'javaw.exe'));
  for (const root of [env.ProgramFiles, env['ProgramFiles(x86)']].filter(Boolean)) {
    for (const vendor of ['Eclipse Adoptium', 'Java', 'Microsoft', 'Zulu', 'Amazon Corretto']) {
      const dir = path.join(root, vendor);
      let names = [];
      try {
        names = fs.readdirSync(dir);
      } catch {
        continue;
      }
      // Newest first, so jdk-21 wins over jdk-17.
      for (const name of names.sort().reverse()) list.push(path.join(dir, name, 'bin', 'javaw.exe'));
    }
  }
  list.push('javaw');
  return list;
}

function probe(javaw) {
  const java = javaw.replace(/javaw(\.exe)?$/i, 'java$1');
  return new Promise((resolve) => {
    execFile(java, ['-version'], { timeout: 8000, windowsHide: true }, (err, stdout, stderr) => {
      if (err && !stderr) return resolve(0);
      resolve(parseJavaMajor(`${stdout}\n${stderr}`));
    });
  });
}

/** Returns { javaw, major } for the first install at or above `minMajor`, or null. */
async function findJava(minMajor, env = process.env) {
  for (const javaw of candidates(env)) {
    if (path.isAbsolute(javaw) && !fs.existsSync(javaw)) continue;
    const major = await probe(javaw);
    if (major >= minMajor) return { javaw, major };
  }
  return null;
}

/** Installs a JDK with winget (per-user prompt from Windows may appear). Resolves to the exit code. */
function installJava(packageId, onLine) {
  return new Promise((resolve, reject) => {
    const p = spawn(
      'winget',
      ['install', '--id', packageId, '--silent', '--accept-package-agreements', '--accept-source-agreements'],
      { windowsHide: true },
    );
    p.stdout.on('data', (d) => onLine && onLine(String(d)));
    p.stderr.on('data', (d) => onLine && onLine(String(d)));
    p.on('error', reject);
    p.on('close', resolve);
  });
}

module.exports = { parseJavaMajor, findJava, installJava, candidates };
