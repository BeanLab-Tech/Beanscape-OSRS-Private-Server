'use strict';

const fs = require('node:fs');
const https = require('node:https');
const net = require('node:net');

/** HTTPS download with redirect following; writes to `dest.part` then renames. */
function download(url, dest, onProgress, redirects = 5) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    if (u.protocol !== 'https:') return reject(new Error('refusing non-https download'));
    https
      .get(u, { headers: { 'User-Agent': 'beanscape-client' } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          if (redirects <= 0) return reject(new Error('too many redirects'));
          return resolve(download(new URL(res.headers.location, u).toString(), dest, onProgress, redirects - 1));
        }
        if (res.statusCode !== 200) {
          res.resume();
          return reject(new Error(`download failed: HTTP ${res.statusCode}`));
        }
        const total = Number(res.headers['content-length'] || 0);
        let got = 0;
        const part = `${dest}.part`;
        const out = fs.createWriteStream(part);
        res.on('data', (chunk) => {
          got += chunk.length;
          if (onProgress && total) onProgress(got / total);
        });
        res.pipe(out);
        out.on('finish', () => {
          out.close(() => {
            fs.renameSync(part, dest);
            resolve(dest);
          });
        });
        out.on('error', reject);
        res.on('error', reject);
      })
      .on('error', reject);
  });
}

/** True when a TCP connection to host:port opens within `timeoutMs`. */
function tcpReachable(host, port, timeoutMs = 3000) {
  return new Promise((resolve) => {
    const sock = net.connect({ host, port });
    const done = (ok) => {
      sock.destroy();
      resolve(ok);
    };
    sock.setTimeout(timeoutMs, () => done(false));
    sock.once('connect', () => done(true));
    sock.once('error', () => done(false));
  });
}

module.exports = { download, tcpReachable };
