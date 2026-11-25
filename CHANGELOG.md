# Changelog

Bug fixes for the Beanscape server, launcher, and admin plugin. Add new entries at the top. One entry per fix: the date, what broke, and what changed.

## 2026-11-25

### The world map did nothing

Clicking the globe next to the minimap did nothing, because RsMod has no world map handler. It now opens the full world map in the map slot of every layout, and clicking the globe again, the map's close button or Escape closes it. While the map is open your position marker follows you. The fullscreen option opens the same floating map. Needs a server restart. Relog after it comes back.

### Market Hub is now a layout file

The hub is described by `hub-layout.json` (floors, rooms with walls and doors, objects, NPCs, arrival and leave-portal tiles) instead of code. It is validated on load with every problem listed, a bad override at `.data/hub-layout.json` is ignored with a warning, and objects, NPCs and walls can change with only a restart. Floors still need `generateHubTerrain` and a cache repack. The converted layout reproduces the previous terrain byte for byte. Format and workflow: `docs/HUB_LAYOUT.md`. This is the base for the planned web editor, in-game commands and client plugin tab.

### Hub walls and portal were invisible: the hub sat in the instance area

The Market Hub was at x 9024, inside the tiles the engine reserves for instances (x 6432 and up). Objects placed there go to the instance registry instead of normal zone storage, so the walls, boards and leave portal were never sent to the client; only the NPCs and floor showed. The hub now sits at x 5120 to 5219 (map squares 80 to 81), below that area. Needs a cache repack and a server restart.

### Agility, Slayer, Hunter, Farming and Construction had no content; crafting and thieving extras

The last five skills answered "Nothing interesting happens." Each now has its own module. Agility: the Gnome Stronghold, Barbarian Outpost and Draynor rooftop courses, with lap bonuses and marks of grace. Slayer: all nine masters assign tasks from the cache's own task tables, kills count by the npc's slayer category, and points, the enchanted gem and a slayer shop work. Hunter: bird snares, box traps (ferrets and chinchompas), butterfly and impling nets, and impling jars. Farming: allotments, herbs, flowers and hops grow in real patches, also while logged out. Construction: sawmill planks and a starter house (garden, parlour, workshop) with build mode and furniture. Crafting now makes gold and silver jewellery, strings amulets and dyes capes; Thieving adds trapped chests, lock-picking doors, and stall owners and guards who notice thefts. Locked doors no longer open for free. Needs a server restart with the V16 migration. Relog after it comes back.

### Market Hub behind the Soul Wars portal

The Soul Wars portal at the Grand Exchange now sits at (3172, 3489), is recoloured purple, and leads to the Market Hub, a 100x100 black, grey and purple area. Floors are packed into the cache from one layout (`HubLayout`, black and grey stone with purple rugs), and black painted brick walls are spawned around eight shops, the Grand Exchange hall and the message-board plaza. Shopkeepers (Zeke, Horvik, Bob, Aubury, Wydin, a general store, Dommik, Lowe), two bankers and three Exchange clerks stand inside, using the existing shop and bank handlers. The three noticeboards are decoration only; boards you can read and post to are not built. The Exchange is still `::ge` commands. Needs a cache repack (`./gradlew packCache`) and a server restart.

### Admin console showed offline after a restart

The in-client admin API was switched off by the `ADMIN_API_ENABLED=false` systemd drop-in, which only took effect when the server restarted after the Reboot action. The drop-in in `deploy/systemd/osrs-server.service.d/admin-api.conf` is now `true`, so the console comes back online with the next deploy. It still listens on 127.0.0.1:8087 only.

### Soul Wars portal at the Grand Exchange

New module `beanlab-soulwars`. RsMod has no Soul Wars content, so the cache's `soul_wars_edgeville_portal` was never placed or handled. It is now spawned at the Grand Exchange (3165, 3489) and teleports to a blank 100x100 walkable arena at (5120-5219, 3072-3171), well outside every real map square. Its flat terrain (four map squares, `m80_48` to `m81_49`, plus empty `l` files) is packed into the cache by the module's `MapTileBuilder` and `MapLocSpawnBuilder`, so the client has ground to draw. Its edges are blocked, and a Soul Wars leave portal inside returns to the Exchange. To deploy, stop `osrs-server`, run `./gradlew packCache` in the VM checkout, then start the server. Without the pack step the portal works but the arena draws as void.

### Launcher and plugins now live in the GitHub repo

The launcher (`client/launcher`), the Admin plugin (`client/microbot/admin-plugin`) and the Companion plugin (`client/microbot/companion-plugin`) are now committed to `main` of the server repo, alongside the server content modules from the entries below. `CHANGELOG.md` sits at the repo root and the Updates tab finds it there or in the old workspace layout. The client files keep the pinned Microbot 2.0.20; main's Microbot 2.6.65 update was not taken because it cannot finish the JS5 handshake on revision 233. Run `git pull` in the repo to stay current.
### Herblore, runecraft, fletching, thieving, and crafting had no content

Five skills answered "Nothing interesting happens." Each now has its own module, rewritten from the Lost City tables onto this engine. Herblore cleans 19 herbs and mixes 16 unfinished and 21 finished potions. Runecraft covers 11 altars, their ruin entrances, and the exit portals. Fletching cuts logs into shafts and bows, strings bows, and makes headless arrows, arrows, and darts. Thieving covers pickpocketing (11 NPC groups, stun on failure) and 7 market stalls. Crafting cuts gems, works leather, dragonhide, studded armour, battlestaves, and glass, softens clay, fires pottery, and spins wool and flax. Tiara binding, combination runes, guard reactions, bolts, jewellery, and cape dyeing stay out for now. Needs a server restart. Relog after it comes back.

### Every shop sold the Lumbridge general store

Trade opened one shared general store for every shopkeeper. The cache names each shop (`axeshop`, `runeshop`, `generalshop2`) but does not include the stock. Stock for 105 shops now comes from the Lost City shop lists, which use those same names. Named keepers open their own shop, and the rest match by cache name. A general store still buys most items. A specialty shop buys only its own stock. Bob and the Lumbridge general store keep their existing scripts. Needs a server restart. Relog after it comes back.

### Bank booths and chests did nothing

Clicking a banker was wired, but almost every booth and chest was not. Upstream only opens the bank for two booth types, so the rest answered "Nothing interesting happens." Any unscripted loc with a Bank option, or a Use/Open option on a bank booth or bank chest, now opens the bank. The two booths that already had a script keep it. Needs a server restart. Relog after it comes back.

### Level-ups, standard spells, and fishing

Level-ups now send the congratulations lines, a sound when the cache has one, and the fireworks graphic. The standard spellbook can cast Home Teleport (30 minute wait), the city teleports, and low and high alchemy. Fishing spots whose name contains "fish" can be netted, baited, lured, caged, or harpooned. Needs a server restart. Relog after it comes back.

## 2026-11-24

### Admin console redesigned

The Beanscape Admin window now follows the OPClient look: a sidebar with the mark and your signed-in account, a status bar, and pages that reflow. The window resizes (minimum 820 by 560); under 940 px the sidebar shrinks to icons, and under about 600 px of content the two-column pages stack. Confirmations, the typed delete check and the one-time password use themed dialogs instead of the system ones. Players, Accounts and Items have search fields with live results, empty states, and wrapped action groups; Stats and World scroll instead of clipping.

### Item search separates working items from leftovers

The server now tags each search result as gear, consumable, usable, misc or unusable (`kind`), plus `tradeable` and `members`. Unusable means a dummy or beta name, a dummy item, or an item with no use, no trade value and no price. The console lists working items first, shows a tag on each row, and hides unusable items behind a "Show unusable" switch. Needs the server rebuild; against an older server every result shows as Misc.

### Item pictures failed to load

Icons were fetched from the wiki by item name only, so duplicate or oddly named items never loaded. The console now draws the client's own sprite by item id first and falls back to the wiki by name (also trying the detail image). Items with no picture anywhere show a "?" instead of an empty box.

### Discord profile pictures for accounts

Players, Accounts, the Items target and the sidebar now show a round Discord avatar. Migration `V15__discord_avatar.sql` adds `accounts.discord_avatar`; Discord registration stores the hash at sign-in (and refreshes it when a bound user signs in again); `/v1/players`, `/v1/accounts` and `/v1/me` return `avatar` and `discord`. Accounts with no stored hash get Discord's default avatar for their id. Run `scripts/backfill_discord_avatars.py` on the VM once to fill in existing accounts with their real pictures. Needs the server rebuild and restart to apply the migration. The server tests pass locally (28); not yet run against the live server.

### Launcher rebuilt around the OPClient layout

The launcher now has the same shell as OPClient. Home shows the selected character with their skills, total level, combat, wealth and playtime, a Progress chart (XP, wealth or levels over 7 days, 30 days or all time) and an Account feed. A Live clients bar in the sidebar shows each running client's RAM, CPU, script and uptime. Characters lists every account with its stats. Sessions lists running clients with Restart and Stop, and you can run more than one account at once. Updates shows this changelog plus the state of the launcher, plugins and Microbot. Settings adds an accent color (7 options) and a background (6 options), plus memory per client.

### New Beanscape Companion plugin

The launcher reads a character's stats from a new read-only Microbot plugin, `beanscape-companion-plugin`, ported from the OPClient Companion. It writes skills, XP, wealth, account type, membership, playtime, world and quest points to the client's isolated home, and logs level-ups, quests, diaries, combat tasks, collection log items, clues, pets and valuable drops for the Account feed. Play installs and updates it automatically. It compiles against Microbot 2.0.20 and its tests pass. Not yet confirmed in game on revision 233: log in once and check that Home fills in. The wealth, playtime and membership readers rely on varps that may differ on this server.

### Inventory looked transparent with the setting off

Cache script 907 compared `side_transparency` the wrong way round, so the inventory was drawn see-through even with transparency turned off. The comparison was corrected.

### NPCs only spawned in the Lumbridge area

A new `beanlab-spawns` module adds a startup script that queues world NPC spawns outside Lumbridge from a packed resource. It needs a server restart to take effect.

### Settings window stayed open

Opening settings put interface 134 (`settings`) over the game. The cache has a `settings:close` button, and no script handled it, so the window stayed up until logout. `SettingsSideScript` on the game VM now closes that overlay when the button is clicked. The server was restarted at 22:35 MST. The side tab still stays open until another tab is selected.

### Login failed with "Error connecting to server"

The game VM's files had been switched to an unpublished revision 243 build. That cache's map-key file was empty, so the world could not load. The process players were connecting to was still revision 233. The protocol sources, `osrs-233` libraries, and revision 233 cache were restored, and the server was restarted. Login then succeeded with the Beanscape launcher on Microbot 2.0.20. A revert snapshot is on the VM at `/opt/osrs/backups/2026-11-24-pre-233-restore`.

### Discord-created characters could not finish login

New characters were inserted without `characters.last_logout`. RsMod treats that null as an invalid save and the client reports "Could not complete login." `last_logout` was set on Umpix's characters. Coordinates were already present and were not the cause.
