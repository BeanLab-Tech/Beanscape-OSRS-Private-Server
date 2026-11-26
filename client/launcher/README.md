# Beanscape Client

Electron launcher that runs **Microbot 2.0.20** against the BeanLab private OSRS server
(`../server-repo`). It does what `client/microbot/` in the server repo does by hand, in one click.

## Run

```powershell
npm install
npm start
npm test
```

## What PLAY does

1. Finds Java 17+ (`JAVA_HOME`, Program Files, PATH). "Install Java" runs `winget` for Temurin 21.
2. First run only: downloads `microbot-2.0.20.jar` from the Microbot release, checks its SHA-256,
   then patches two constants (server RSA modulus, host allowlist), like `patch-microbot.py`.
3. If an account is selected, writes that username and password into the isolated Microbot
   profile (legacy login) and starts with `--profile`. Passwords are encrypted with Windows
   DPAPI and are not written into the launcher log.
4. Starts Microbot with `--jav_config=<server>` and `-Duser.home=<data>\home`, so the normal
   RuneLite install is never touched.

Add accounts from the launcher: username and password only, the same login the BeanLab client
asks for. A brand-new account is still created with Discord registration.

## Tabs

Home (character sheet, progress chart, account feed), Characters, Sessions, Updates, Settings. Stats come from the
**Beanscape Companion** plugin (`../beanscape-companion-plugin`), which Play installs into the isolated home. Build a new
jar with `gradle installToClient` there; it copies into `plugins/` here. Bump the version in `plugins/manifest.json`.
The Updates tab renders `../CHANGELOG.md`, so add every change there.

Data lives in `%APPDATA%\Beanscape Client`. Requires Tailscale joined to the tailnet.

Server host, modulus and the Microbot version/checksum are all in `electron/server-config.cjs`,
one entry per server (`live`, `staging`). Settings → Server picks which one Play starts.

**Staging** is the OpenRune test world (rev 242, world 2) on the same VM. It pins Microbot
2.6.57 (class names `ar.class`/`ud.class` in `patch`), keeps its client and home under
`servers\staging`, and connects on port 40002: its jav_config
(`https://runescape-server.tail5475a4.ts.net/staging/jav_config.ws`) sets `param=15=1`, so the
client uses 40000 + world id instead of 43594. Live keeps the original data layout.
If the server's RSA key changes, update the modulus there and press **Rebuild client**.
Do not move to a newer Microbot: the server is pinned to rev 233. The live server currently
publishes 2.6.65, which cannot complete the JS5 handshake. This launcher ignores that and
stays on 2.0.20.
