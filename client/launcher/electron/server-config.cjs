'use strict';

// Everything server-specific lives here so a key or host change is a one-file edit. None are secret.
// Each server pins its own Microbot build: the client must match the server's OSRS revision.

const TAILNET_HOST = 'runescape-server.tail5475a4.ts.net';
const VM_TAILNET_IP = '100.98.119.38';

const SERVERS = {
  // Live: RsMod rev 233 (values from client/microbot + proxy-targets.yaml).
  live: {
    id: 'live',
    name: 'BeanLab OSRS',
    // Points RuneLite's API (world list, item prices) at the server, like Play-Microbot.bat does.
    httpServiceUrl: `https://${TAILNET_HOST}/runelite`,
    javConfigUrl: `https://${TAILNET_HOST}/jav_config.ws`,
    // Admin console API (owners only), served through tailscale serve next to the web admin.
    adminApiUrl: `https://${TAILNET_HOST}/adminapi`,
    registerUrl: `https://${TAILNET_HOST}/oauth/discord/start`,
    // Direct game port check (tailnet IP of VM 123). Reachable only when Tailscale is connected.
    gameHost: VM_TAILNET_IP,
    gamePort: 43594,
    // Server's public RSA login modulus (1024-bit, hex).
    modulus:
      'd6ac773e2baf663e78d82337e3f1e4ccd46e2b4b4235e6be93f11bbb445a3c7a3a01f1dac3feee2a0e14740cda715ec18f6d746e67ef7a50e876041f6ac3db95abbf8b6ce9973cc05d134d4afa35d749831fb2073f42644360d6da342d62f95479c2be2650cade3aeba76e73a05495fe8f6bb871dcb2e657b34e20b0ed94bf13',
    // Rev 233, so only Microbot 2.0.20 (RuneLite 1.11.19) can log in.
    microbot: {
      version: '2.0.20',
      url: 'https://github.com/chsami/Microbot/releases/download/2.0.20/microbot-2.0.20.jar',
      sha256: '95a652d44afe867b5b18879ef5ddf56efb0f0a43b005e26c7018eafde8587dd6',
    },
    // Obfuscated class names inside that jar (they change every revision).
    patch: {
      modulusClass: 'bc.class',
      allowlistClass: 'bl.class',
      jagexModulus:
        '8b3a6290fcfe8f8d0752bfd815158328bf43b89e91356380968f51465f29f689dde6c169375e12dd28a43633b619a48f71c539c9f1f4e59aadd5e2ad681f3e1270313ef044889faee740f641ea0f8b341d44f388758e12b893f46fb7515ed8dc9b6ded076f1a621cf0d6f2157a32434e5f3028e3baf3ff2f08ab86de8776638f',
    },
  },

  // Staging: OpenRune rev 242 on the same VM (world 2). Its jav_config sets param 15 (build) to 1,
  // so the client connects to 40000 + world = 40002 instead of 43594, next to the live server.
  staging: {
    id: 'staging',
    name: 'Beanscape Staging (OpenRune 242)',
    // Unset: RuneLite's own API serves world list and prices for rev 242.
    httpServiceUrl: null,
    javConfigUrl: `https://${TAILNET_HOST}/staging/jav_config.ws`,
    adminApiUrl: `https://${TAILNET_HOST}/adminapi-staging`,
    registerUrl: `https://${TAILNET_HOST}/oauth/discord/start`,
    gameHost: VM_TAILNET_IP,
    gamePort: 40002,
    modulus:
      '8a6ff7e30bc475169a971ff148caa8d2c2f8efcb204b778ee9fd7422740e9c6747c34bcc6cd0811a8a01067b9a53eb0c40f9dbd90a34e24c3074e2d54a86d2ddacb385c9365f3729806f6a82dbd78e90965d2ee86f76920ad631f206acc69417f1b317f59cb0555ac0f8e66fe20044e74c75b1af6929d96d99bc3129ed17eefb',
    // Rev 242 = Microbot 2.6.57 (RuneLite 1.13.6). Bump together with OpenRune's revision.
    microbot: {
      version: '2.6.57',
      url: 'https://github.com/chsami/Microbot/releases/download/2.6.57/microbot-2.6.57.jar',
      sha256: '62fdf80d034a755bdbb12ad0eac6a6cd05d91bbebdece081d87b3a23b200095f',
    },
    patch: {
      modulusClass: 'ar.class',
      allowlistClass: 'ud.class',
      jagexModulus:
        '876c76890d0357647becb17ef5c26d201e94abfbf4c8ef66d744ac78703c26bdc6af4a52b0b78bee6bf19336b395fdf14d9e466547199869b4f97d078123efc0ba0fc2ccda848232ea572a740315a82b12521182a6522cb6c6d81291d53383fc0d636d4a07134bdb4e09be9a6e1a62180a96281d754ea067481a7d8881837c17',
    },
  },
};

const COMMON = {
  minJava: 17,
  javaInstallId: 'EclipseAdoptium.Temurin.21.JDK',
  heapMb: 2048,
};

const DEFAULT_SERVER = 'live';

/** The config for server `id`; unknown ids fall back to live. */
function forServer(id) {
  return { ...COMMON, ...(SERVERS[id] || SERVERS[DEFAULT_SERVER]) };
}

module.exports = { SERVERS, DEFAULT_SERVER, forServer };
