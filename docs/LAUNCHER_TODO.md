# Launcher: audit follow-up

The audit of 2026-10-09 listed 54 things in the launcher that were fake, hardcoded or missing. All of them are now built or removed. Setup (keys, backend env) is in [STATUS.md](STATUS.md).

## What changed, by audit item

| # | Item | Now |
|---|------|-----|
| 1, 40 | Check for updates, self-update | Signed manifest from the backend (`/v1/manifest/stable`), Ed25519-verified with `manifest_public_key`; downloads the installer for this platform, checks SHA-256, runs it. The menu item only shows when updates are configured. The Kite client jar also updates from the manifest before launch. |
| 2 | Upload logs | "Share logs" uploads the launcher log (now written to `logs/launcher.log`) and the profile's latest game log to mclo.gs, with access tokens removed first. Also on the crash card. |
| 3 | Export .mrpack | Save dialog, pack name/version, extra folders to include. Modrinth content becomes downloads; hand-added files and chosen folders go into `overrides/`; disabled content is left out. |
| 4, 5, 13 | Worlds | Read from `saves/*/level.dat` (name, mode, version, last played, size, icon). Play straight into a world (1.20+), back up to `backups/`, restore, delete (type the name to confirm). |
| 6, 8 | Server and friend Play | Quick play: `--quickPlayMultiplayer` where the version supports it, `--server/--port` on older versions. The game now sends its server in the presence heartbeat, so friends' Join works. |
| 7, 14 | Servers | `servers.dat` read and edited (add, remove), live Server List Ping (players, latency, MOTD, icon; SRV records followed). |
| 9, 10, 15 | Gallery | Real screenshots through the asset protocol, lightbox with arrow keys, copy image to clipboard, show in folder, delete. |
| 11 | Enable/disable content | Renames to `.disabled` and keeps the flag in the lock file; updates keep it disabled. Files added by hand are listed too, can be switched off, removed, or recognised on Modrinth by hash so they get updates. |
| 12 | Hide when the game starts | Tray icon (show, stop the game, quit). The window comes back when the game exits or crashes. |
| 16 | Loader version | Real lists from Fabric/Quilt meta and the Forge/NeoForge mavens; a pinned version is used at launch. |
| 17 | Folder buttons | Open saves, screenshots, mods, resource packs, shader packs, backups, crash reports. |
| 18, 19, 37 | Friends, chat, presence | Backend: friends, requests (by name, auto-accept when both ask), blocks, messages with unread counts, polling for new messages and requests (notifications respect the toggle), status picker sent as a launcher heartbeat. Invisible hides status and last seen. |
| 20, 21, 23 | Store, coins, history | Backend catalogue with prices (epic 600, legendary 1200; common and rare stay free), store art from the client's cosmetic assets (capes cropped from the texture), buy with coins, equip/take off, transaction ledger. |
| 22 | Buy coins | Tebex Headless checkout in the browser, signed webhook credits coins once (refunds and disputes take them back). Shows "not available yet" until Tebex is configured. |
| 24, 25, 27 | News, banners, server list | Backend feeds managed with admin routes (`backend/seed/feeds.example.json`). Explore also lists every profile's own servers. |
| 26 | Players online | Count of distinct players with a heartbeat in the last 90 s. Hidden without a backend. |
| 28 | Notification seed | Starts empty; old sample notifications are cleared once. |
| 29 | CurseForge | Real client: search, project pages (HTML description in a sandboxed frame), versions, dependencies, installs, updates, modpacks (online and `.zip` import). Files whose authors block third-party downloads are reported, not fetched. Without a key the page says how to add one. |
| 30–33 | Resolution, fullscreen, hooks, env | Applied at launch. Hooks run in the game folder with `INST_ID`, `INST_NAME`, `INST_DIR`, `INST_MC_DIR`, `INST_JAVA`; a failing pre-launch command stops the launch. |
| 31 | Game directory | Removed globally; per profile "Game folder" instead. |
| 34 | Storage | Profile format 2 (`overrides`, `meta`, `loader_version`, `game_dir` in `profile.json`) and `settings.json`. Old localStorage data migrates once, including disabled mods. |
| 35 | Built-in mods only | The loader gets a mods folder with just the Kite jar (`fabric.modsFolder` / `loader.modsDir`). |
| 36 | Language pref | Removed (there's no translation yet). |
| 38 | Forge / NeoForge | Installer processors run with the profile's Java and are cached under `loaders/`. Live-tested: Forge 1.20.1, NeoForge 1.21.1, Forge 1.12.2 boot. |
| 39 | Stop the game | Stop on Home, the profile card, the profile panel, Ctrl+K and the tray; kills the whole process tree. Also cancels a launch that's still preparing. |
| 41 | Java | Detected installs (with version) or any `java` executable, per profile or global, checked when picked. |
| 42 | Crash "View log" | Opens the crash report or the full log; Home's crash card adds the logs folder and log sharing. |
| 43 | Datapack world | Dropdown of the profile's worlds. |
| 44 | Microsoft sign-in | Browser sign-in (auth code + PKCE with a loopback redirect): email and password, passkeys, 2FA on Microsoft's page. "Use a code instead" keeps the device-code flow. |
| 45 | Profile card Play | Launches (or stops the running game). |
| 46 | Placeholder links | Help and source link to the GitHub repo; the privacy and terms entries are gone until those pages exist. |
| 47 | Ad slot | Removed. |
| 48 | User agent | `lorak12/kite-mc-client/<version> (https://github.com/lorak12/kite-mc-client)`. |
| 49 | Supported versions | One table in `runtime.rs`, read by the UI through `kite_versions`. |
| 50 | Licenses | `npm run licenses` generates `src/licenses.json` from `cargo metadata` and the npm lockfile (runs in `npm run build`); searchable list in the app. |
| 51 | Skin heads from mc-heads.net | Kept (works, cached by the browser). |
| 52 | Covers | Upload your own image (copied into the profile, shown through the asset protocol). |
| 53 | Stage labels | One list in `lib/launch.ts`. |
| 54 | CSP | Real CSP (scripts from the app only, images from https/asset/data, no objects or forms). |
| Cleanup | | `search_content` and `has_account` removed, `api/mock` deleted, preview hints gone. The browser dev mock (`src/devMock.ts`) stays for `npm run dev` only. |

## Still to do outside the code
- **Deploy the backend** somewhere public with `KITE_JWT_SECRET`, `KITE_ADMIN_TOKEN` and a persistent `KITE_DB`, then set `backend_url` for players.
- **Azure**: add `http://localhost` as a "Mobile and desktop applications" redirect URI for browser sign-in.
- **Tebex**: create the store (Headless API, no username sign-in), one package per coin bundle, a webhook to `/v1/webhooks/tebex`. Checkout and webhook handling are tested against fakes, not a live Tebex store.
- **Releases**: generate an Ed25519 key pair, set `KITE_MANIFEST_SEED` on the backend and `manifest_public_key` in the shipped config, build with the MSVC toolchain, publish the manifest.
- ~~**In-game wardrobe**: lock unowned items.~~ Done (task 28): unowned items are locked with their price and can be bought in game.

## Manual checks in the desktop app
1. First start after updating: profiles keep their memory/JVM/GC settings, covers, descriptions and disabled mods; Settings > Java shows your old global values.
2. Settings > Accounts > Add Microsoft account: the browser opens, signing in finishes in the launcher; "Use a code instead" still works.
3. Create a Forge 1.20.1 profile and a NeoForge 1.21.1 profile; both reach the title screen (first launch runs the installer, later ones skip it).
4. Profile settings > Mods: switch a mod off, launch, check it isn't loaded (`mods/` shows `.disabled`). Drop a jar into `mods/`, it shows as "Added by hand"; Recognise files picks up Modrinth ones.
5. Worlds: Play opens the world directly (1.20+); Back up then Restore creates a copy.
6. Servers: Add a server, the ping shows players; Play joins it. Remove it.
7. Gallery: take an F2 screenshot in game, it shows up; Copy pastes into an image editor; Delete removes the file.
8. General: pin a loader version and launch; upload a cover image; choose a custom game folder; Export .mrpack and import the file again as a new profile.
9. Java and game: set a resolution and fullscreen; set a pre-launch command that fails (`exit 1`) and check the launch stops; set an env var and see it in the game log.
10. Set "When the game starts" to Hide to tray: the window hides, the tray icon shows it again, and it comes back when the game closes.
11. Stop the game from Home and from the tray. Crash it (e.g. a broken mod): the crash card's Crash report, Full log and Share logs buttons work.
12. App menu: Share logs gives two mclo.gs links without your access token in them; Licenses lists the packages.
13. Content on CurseForge (with a key): search, open a project page, install a mod with dependencies, install a modpack.
14. With the backend running: two accounts (offline accounts work with `KITE_ALLOW_OFFLINE=1`) add each other, chat, see each other's status; when one is on a server in game, the other's Join button launches into it.
15. Store: buy an epic item with admin-granted coins, equip it, see it in game; the transaction shows in the coin history.
16. Shared Minecraft settings: change the FOV and a keybind in one profile, quit, launch another (e.g. an imported modpack): same FOV and keybind, its own resource packs. Turn "Same settings in every profile" off for one profile and its changes stay local.
