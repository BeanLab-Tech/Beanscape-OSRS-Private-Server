# CLAUDE.md — BeanLab OSRS Private Server

@AGENTS.md

---

## Claude-specific notes

- This repo is an overlay. Do not try `./gradlew` at the repo root — run
  `scripts/build-overlay.sh` (needs network for the first upstream clone and Gradle downloads;
  Maven Central sometimes returns 429, so retry with backoff).
- To read upstream APIs, use `build/upstream/` after one script run (or clone `rsmod/rsmod` at
  the pinned commit). Verify RsMod signatures there rather than guessing; e.g. `ObjTypeList` is a
  `Map<Int, UnpackedObjType>`, and `ProtectedAccess` has the inventory helpers.
- VM 123 (`ssh root@100.98.119.38`, tailnet only) is not reachable from cloud sessions. Anything
  that needs the live server (deploying, SQL against `game.db`, in-game tests) goes to the user as
  explicit steps; see `docs/AUDIT_2026-10-29.md` §4 for the pattern.
- Keep `README.md` (players/admins), `AGENTS.md` (agents) and the audit consistent when behaviour
  changes — they drifted badly before.
