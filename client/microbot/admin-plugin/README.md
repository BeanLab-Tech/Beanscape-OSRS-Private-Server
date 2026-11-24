# Beanscape Admin plugin

An admin console for the BeanLab server, as a Microbot side-loaded plugin: players, items, stats,
accounts, world tools (broadcast, reboot, teleports) and an audit log. It talks to the server's admin
API (`content/other/admin-api`) over the tailnet. Owners only for now.

It is a normal RuneLite plugin loaded from `home\.runelite\microbot-plugins`, so it needs no change
to the patched Microbot jar and survives `Update-Client.ps1`.

## Install (owners)

1. Have the client set up first (`Update-Client.ps1` / README steps 1 to 5), so Microbot 2.0.20 is
   in `OSRS-Microbot-Client\original`.
2. In game, as an owner, run `::admintoken` and copy the token (shown once; `::admintoken revoke`
   revokes yours).
3. From a clone of this repo, with Microbot closed:

   ```powershell
   powershell -ExecutionPolicy Bypass -File client\microbot\Install-AdminPlugin.ps1 -Token bsa_...
   ```

4. Start `Play-Microbot.bat`. The **Beanscape Admin** button appears in the side toolbar; **Open
   console** opens the full window.

The server must have the API switched on: it ships with `ADMIN_API_ENABLED=false`
(`deploy/systemd/osrs-server.service.d/admin-api.conf`) and needs
`tailscale serve --bg --set-path /adminapi http://127.0.0.1:8087`. See `docs/ADMIN_API_DEPLOY.md`.

## Build by hand

```powershell
cd client\microbot\admin-plugin
.\gradlew.bat jar                                  # build\libs\BeanscapeAdminPlugin.jar
.\gradlew.bat installToClient                      # also copies it into the client's home
.\gradlew.bat jar -PclientDir=D:\Games\Beanscape   # client folder somewhere else
.\gradlew.bat jar -PmicrobotJar=C:\path\microbot-2.0.20.jar
```

Needs JDK 17 or newer (it compiles for Java 11). It compiles against the stock Microbot 2.0.20 jar and
does not bundle it.

## Settings file

`home\.runelite\beanscape-admin.json`, written by the install script:

```json
{"apiUrl": "https://runescape-server.tail5475a4.ts.net/adminapi", "token": "bsa_..."}
```

Treat the token like a password. It only works for owner accounts, and the server reads the account's
current mod level on every request.

## Developing the UI without a server

`dev/mock-server.cjs` is a stand-in for the API (fake players and accounts, no game needed):

```powershell
node dev\mock-server.cjs        # http://127.0.0.1:8099, token dev-token
```

Point `beanscape-admin.json` at `http://127.0.0.1:8099` with token `dev-token`. The data is invented.

## Layout

| Path | What |
|---|---|
| `src/.../BeanscapeAdminPlugin.java` | plugin entry, toolbar button, small side panel |
| `AdminWindow.java`, `*Page.java` | the console window and its pages |
| `Session.java`, `ApiClient.java` | polling, shared state, HTTP calls |
| `Theme.java`, `Ui.java` | stone-and-gold look, shared with the Woodcutting plugin |
| `WikiIcons.java` | item icons from the OSRS wiki, cached under `.runelite\beanscape-admin\icons` |
