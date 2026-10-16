# Roadmap

What is still open, and what is already done. The detailed state of each part is in [STATUS.md](STATUS.md).

## Open

1. Add optimizations to the client.
2. Add more cosmetics to the store.
3. Go through every mod, cross-check it with Lunar Client, and keep the ones worth having.
4. Fix the server entry (needs more detail on what is wrong).
5. Build real in-game chat (the Chat button is a "coming soon" panel) and emotes (the hub tile is "soon").
6. Launcher go-live: deploy the backend, set up the Tebex store and sign releases. See [LAUNCHER_CHECKLIST.md](LAUNCHER_CHECKLIST.md).
7. Check each client version in game. See [MULTIVERSION_TEST_CHECKLIST.md](MULTIVERSION_TEST_CHECKLIST.md).

## Done

- Sign-in: Microsoft browser sign-in (email and password, passkeys and 2FA happen on Microsoft's page), with a device-code fallback.
- Modrinth and CurseForge support, including modpack download and import.
- Mod list search in game, and the in-game UI remake (see [the design spec](superpowers/specs/2026-10-08-ingame-ui-overhaul-design.md)).
- Store and wardrobe with coins, in the launcher and in game.
- Sample profile files are in `example/`.
