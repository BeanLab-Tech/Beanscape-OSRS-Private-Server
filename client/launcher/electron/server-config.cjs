'use strict';

// Everything server-specific lives here so a key or host change is a one-file edit.
// Values come from the BeanLab README (client/microbot + proxy-targets.yaml). None are secret.
module.exports = {
  name: 'BeanLab OSRS',
  // Points RuneLite's API (world list, item prices) at the server, like Play-Microbot.bat does.
  httpServiceUrl: 'https://runescape-server.tail5475a4.ts.net/runelite',
  javConfigUrl: 'https://runescape-server.tail5475a4.ts.net/jav_config.ws',
  // Admin console API (owners only), served through tailscale serve next to the web admin.
  adminApiUrl: 'https://runescape-server.tail5475a4.ts.net/adminapi',
  registerUrl: 'https://runescape-server.tail5475a4.ts.net/oauth/discord/start',
  // Direct game port check (tailnet IP of VM 123). Reachable only when Tailscale is connected.
  gameHost: '100.98.119.38',
  gamePort: 43594,
  // Server's public RSA login modulus (1024-bit, hex).
  modulus:
    'd6ac773e2baf663e78d82337e3f1e4ccd46e2b4b4235e6be93f11bbb445a3c7a3a01f1dac3feee2a0e14740cda715ec18f6d746e67ef7a50e876041f6ac3db95abbf8b6ce9973cc05d134d4afa35d749831fb2073f42644360d6da342d62f95479c2be2650cade3aeba76e73a05495fe8f6bb871dcb2e657b34e20b0ed94bf13',
  // The server is pinned to OSRS rev 233, so only Microbot 2.0.20 (RuneLite 1.11.19) can log in.
  microbot: {
    version: '2.0.20',
    url: 'https://github.com/chsami/Microbot/releases/download/2.0.20/microbot-2.0.20.jar',
    sha256: '95a652d44afe867b5b18879ef5ddf56efb0f0a43b005e26c7018eafde8587dd6',
  },
  minJava: 17,
  javaInstallId: 'EclipseAdoptium.Temurin.21.JDK',
  heapMb: 2048,
};
