# Task 26: Multi-version client (user request, 2026-10-09)

"Add a mod harness version for each Minecraft version from 1.7.10 thru 1.16.4 and 1.18.2 up to 26.3, marking each
major version release so not every version; see how other launchers choose; make sure our set of mods work and the
mod loader is also working."

Version choice (Lunar / Feather / Badlion all ship 1.7.10, 1.8.9, 1.12.2, 1.16.5 and skip 1.9-1.11, 1.13-1.15;
1.16.5 is the same protocol as 1.16.4), then the last patch of each release / game drop:
1.18.2, 1.19.4, 1.20.1, 1.20.4, 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, 26.1.2, 26.2, 26.3.

1. Build infra: shared adapter Gradle logic; Legacy Fabric (legacy-looming) build for 1.7.10 / 1.8.9 / 1.12.2.
2. Launcher: SUPPORTED table for every adapter; Fabric profiles below 1.14 install Legacy Fabric; tests.
3. One adapter per version: entrypoint, HUD/tick/input hooks, menu screen, GameState, UiRenderer, Overrides mixins.
4. Verify: compile, mixin audit boot per version, launcher live boot (loader install + "Aether loaded").
5. Docs (STATUS.md) + in-game checklist.
