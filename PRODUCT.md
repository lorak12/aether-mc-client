# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users
Public Minecraft players who want one place to install, launch and manage the game, and a client with mods, cosmetics and social features. Confirmed as the primary audience; they are not limited to a single community or a competitive niche.

## Product Purpose
Kite is a custom Minecraft launcher (Tauri desktop app, Rust core) and in-game client (Java, Fabric adapters). Together they cover game installation and launching, accounts, profiles, mod and modpack management, and client-side features such as a mod menu, HUD editor, cosmetics and a main menu. Success means a player can set up and play a modded profile without leaving one app.

## Positioning
Launcher and client in one product: a full launcher (Modrinth and CurseForge content, Fabric/Quilt and Forge/NeoForge, `.mrpack` and CurseForge modpack import) with a built-in client, instead of separate tools. The client is designed to run alongside other mods such as Sodium, Iris, Lithium and Xaero's maps, without requiring Fabric API. Mod setups are shareable as portable JSON profiles that can be exported, imported and switched per server.

## Operating Context
- Players use it on a desktop: the launcher keeps profiles, worlds, servers and screenshots, and launches the game with the right loader and Java runtime.
- Sign-in is through Microsoft/Xbox (browser or device code). Offline accounts exist only for development and singleplayer testing.
- Profiles and mod settings are plain JSON files (`profile.json`, `config/kite/profiles/<name>.json`) that players can copy and share.
- Social and store features (friends, chat, presence, coins, cosmetics) go through a backend service. Payments go through Tebex.

## Capabilities and Constraints
- Shipping: launcher profiles, Modrinth and CurseForge search/install/update with dependency resolution, Forge/NeoForge install and launch, shared Minecraft options, crash reports, log sharing, tray icon, update check, and the Kite client on Fabric/Quilt for the 33 releases from 1.7.10 to 26.3.
- Shipping in-game: main menu replacement, Right Shift hub, mod window (56 mods), typed settings controls, colour picker with chroma, HUD editor, wardrobe.
- Not built yet: the Kite client on Forge/NeoForge, release signing, a deployed backend, Emotes, a real in-game chat (the Chat button opens a "coming soon" panel), and in-game checks for most of the 33 releases. Cosmetics are not yet verified in game.
- Stack: Rust/Tauri with React + Vite for the launcher UI; TypeScript backend on Node 22.5+ with SQLite; Java 8-compatible runtime built with Gradle.
- Terminology: "profile" is one mod and settings set; "adapter" is a version-specific Fabric build of the shared runtime; "coins" are the in-game currency.
- Undecided: whether the core client stays free and only cosmetics are paid. The repo does not settle this.

## Brand Commitments
- Name: Kite. The brand tokens are in `brand/tokens.json`, and the app icon is `brand/icon.png`. Treat them as the current identity, but no visual direction is binding yet.
- Kite is not an official Minecraft product and is not affiliated with Mojang or Microsoft. Keep this disclaimer visible in product copy.
- The in-game badge (the Kite mark on 1.21.11+, a ◆ on older versions) marks client users. Its meaning is not settled beyond that.

## Evidence on Hand
- Working launcher and client in the repo: `docs/STATUS.md` lists what is built and tested, `docs/LAUNCHER_CHECKLIST.md` holds the audit log and the in-app checklist.
- Verified in-game on Fabric 1.21.11 and 26.3 (`runAutotest`, 2026-09-29), with 26.3 also checked alongside Sodium, Iris, Lithium, ImmediatelyFast, Xaero's maps and EMF/ETF. The 26.1 family, 1.19.3 and 1.19.4 passed `runAutotest` on 2026-10-15.
- No testimonials, customer counts, benchmarks, press or pricing exist. Do not invent them.
- Design specs: `docs/superpowers/specs/2026-10-07-launcher-ui-redesign-design.md` (launcher) and `docs/superpowers/specs/2026-10-08-ingame-ui-overhaul-design.md` (in-game).

## Product Principles
- Everything the launcher does is real: no preview data in shipped flows. Mock data stays behind the `api` layer and is labelled as mock.
- Player setups are portable. Profiles stay plain, exportable files, not locked inside the app.
- The client plays nicely with the mods players already run, so it should not replace or require core mods.
- Be honest about what is unfinished. Mark placeholders as "coming soon" instead of implying features work.

## Accessibility & Inclusion
No product-specific requirement has been set yet. The client already ships reduced motion, menu scale, colourblind colours and a sound-volume setting in General settings, and these should be kept.
