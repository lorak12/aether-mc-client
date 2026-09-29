# Aether

Aether is a personal project exploring a custom Minecraft launcher and client. The goal is to bring game installation and launching, mod and profile management, and client-side features together in one desktop application.

> **Status:** Aether is in very early development. Features and architecture are changing, and it is not ready for general use.

## Project Areas

- **Launcher:** A Rust and Tauri desktop app for accounts, profiles, game setup, launching, and modpack management.
- **Backend:** A TypeScript service for verified sign-in and supporting features such as cosmetics metadata and signed runtime manifests.
- **Runtime:** A Java-based client foundation with a mod API, settings, HUD layout, and an initial set of HUD mods.

Core launcher and backend workflows have tests, but major client features are still unfinished. In particular, in-game rendering and hooks, many client modules, and the integration of cosmetics and badges are not implemented yet. See [docs/STATUS.md](docs/STATUS.md) for current capabilities, setup details, and known gaps.

## Development Checks

Run these from the repository root or the indicated directory:

| Area | Command |
| --- | --- |
| Rust workspace | `cargo test --workspace` |
| Backend | `cd backend` then `npm install` and `npm test` |
| Runtime (Windows) | `cd runtime` then `./gradlew.bat test` |
| Runtime (macOS/Linux) | `cd runtime` then `./gradlew test` |

## Project Notes

- The Minecraft version matrix is in [tooling/versions.toml](tooling/versions.toml).
- Brand tokens are in [brand/tokens.json](brand/tokens.json).
- Aether is not an official Minecraft product and is not affiliated with Mojang or Microsoft.
