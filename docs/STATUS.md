# Status and setup

## Where to put ids and keys
All optional. Set as environment variables, or in `aether.config.json` inside the launcher's app data folder.

| Setting | Env var | Enables |
|---|---|---|
| Azure AD client id | `AETHER_AZURE_CLIENT_ID` | Microsoft sign-in (Settings > Sign in) |
| CurseForge API key | `AETHER_CURSEFORGE_API_KEY` | CurseForge provider (not implemented yet) |
| Backend URL | `AETHER_BACKEND_URL` | Cosmetics / badge / runtime manifest (client side not wired yet) |
| Manifest public key | `AETHER_MANIFEST_PUBLIC_KEY` | Verifying signed runtime updates (not wired yet) |

Backend env: `AETHER_JWT_SECRET` (required in production), `AETHER_ADMIN_TOKEN`, `AETHER_MANIFEST_SEED` (32-byte Ed25519 seed, hex), `PORT`.

## Built and tested
- `launcher/core` (Rust): Mojang manifests, verified downloads, launch args, Microsoft/Xbox login, profiles, Modrinth search/install/update with dependency resolution, `.mrpack` import. `cargo test --workspace`.
- `launcher/src-tauri` + `launcher/ui`: app shell (Home, Profiles, Content, Settings). Compiles; not yet run interactively.
- `backend` (TypeScript): Mojang-verified login, cosmetics catalog, loadouts, presence/badge lookup, signed manifests. `npm test`.
- `runtime/core` + `runtime/mods` (Java 8 compatible): mod API, settings, HUD layout, config, and 10 HUD mods, tested against a fake renderer. `./gradlew test`.

## Running the launcher
- Standalone: `cd launcher/src-tauri && cargo tauri build --no-bundle`, then run `target/release/aether-launcher.exe`.
- Dev with hot reload: `cd launcher/src-tauri && cargo tauri dev`.
- Azure app must: allow personal Microsoft accounts, have "Allow public client flows" = Yes, and be approved for Minecraft services (https://aka.ms/mce-reviewappid).

## Launching (built)
- Play: silent re-login from the keychain refresh token (stored in chunks; Windows caps secrets at 2560 bytes), Mojang Java runtime provisioning, Fabric/Quilt loader install, libraries/natives/assets, log4j config, spawn with output to `<profile>/logs/launcher-output.log`.
- Live check: `cargo test -p aether-core --test live prepare_and_boot -- --ignored --nocapture` (boots 1.21.11 Fabric by default with a placeholder session; `AETHER_TEST_PROFILES=1.21.11:fabric,1.8.9:vanilla`). Verified booting both on 2026-09-29.
- Forge / NeoForge profiles can't launch yet (their installers must be run).

## Not built yet
- Java agent, Mixin hooks, per-version adapters, real GL / Blaze3D renderers, custom in-game screens.
- Remaining mods (bossbar, actionbar, chat, zoom, freelook, fullbright, etc.), optimizer modules.
- CurseForge client, cosmetics rendering, badge rendering, client-side backend calls, CI, signing/updater.

## Notes
- Windows toolchain here is `x86_64-pc-windows-gnu`. Use the MSVC toolchain for release builds of the launcher.
- Version `26.2.1` is not in Mojang's manifest as of 2026-09-29.
