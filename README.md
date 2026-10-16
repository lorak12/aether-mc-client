# Kite

Kite is a personal project for a custom Minecraft launcher and in-game client. The launcher handles game installs, accounts, profiles, mods and modpacks, and launching in one desktop app. The client adds a custom main menu, a Right Shift mod menu, a HUD editor and cosmetics.

> **Status:** about 70% complete and in early development. Features and architecture still change, and Kite is not ready for general use.

## Project Areas

- **Launcher** (`launcher/`): a Rust core and a Tauri desktop app with a React UI. Accounts, profiles, Modrinth and CurseForge content, Fabric/Quilt and Forge/NeoForge installs, launching, worlds, servers, backups and crash reports.
- **Backend** (`backend/`): a TypeScript service on Node 22.5+ with SQLite. It handles Microsoft-verified sign-in, friends and chat, cosmetics and the store, news feeds and signed update manifests.
- **Runtime** (`runtime/`): the Java client. It has a shared mod API with 56 built-in mods, an in-game main menu, wardrobe and HUD editor, and Fabric adapters for 33 releases from 1.7.10 to 26.3 (1.7.10 to 1.12.2 use Legacy Fabric).

The client has been run in game with `runAutotest` on 1.21.11, 26.3, the 26.1 family, 1.19.3 and 1.19.4. The other adapters pass load audits, but their in-game behaviour is still being checked. See [docs/STATUS.md](docs/STATUS.md) for what is built, and [docs/MULTIVERSION_TEST_CHECKLIST.md](docs/MULTIVERSION_TEST_CHECKLIST.md) for the in-game checks still open.

## Getting Started

On Windows, `start.bat` starts the backend and the launcher together. Run `start.bat --help` for the options. Setup for keys and backend settings is in [docs/STATUS.md](docs/STATUS.md).

## Development Checks

Run these from the repository root or the indicated directory:

| Area                  | Command                                              |
| --------------------- | ---------------------------------------------------- |
| Rust workspace        | `cargo test --workspace`                             |
| Launcher UI           | `cd launcher/ui` then `npm install` and `npm test`   |
| Backend               | `cd backend` then `npm install`, `npm run typecheck` and `npm test` |
| Runtime (Windows)     | `cd runtime` then `./gradlew.bat test`               |
| Runtime (macOS/Linux) | `cd runtime` then `./gradlew test`                   |

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs the same checks on every push and pull request.

## Documentation

- [docs/STATUS.md](docs/STATUS.md): what is built and tested, setup, and known issues
- [docs/TODO.md](docs/TODO.md): the roadmap
- [docs/LAUNCHER_CHECKLIST.md](docs/LAUNCHER_CHECKLIST.md): the launcher audit log and manual checks
- [docs/MULTIVERSION_TEST_CHECKLIST.md](docs/MULTIVERSION_TEST_CHECKLIST.md): in-game checks for each client version
- [docs/COSMETICS_GUIDE.md](docs/COSMETICS_GUIDE.md) and [cosmetics/README.md](cosmetics/README.md): making and using cosmetics
- [PRODUCT.md](PRODUCT.md): product purpose, audience and principles

## Project Notes

- The Minecraft version matrix is in [tooling/versions.toml](tooling/versions.toml), and the launcher's supported list is in `launcher/core/src/runtime.rs`.
- Brand tokens are in [brand/tokens.json](brand/tokens.json).
- Kite is not an official Minecraft product and is not affiliated with Mojang or Microsoft.
