# 9 — Expand GameState/Events and implement them in both adapters

## What
Add to GameState (with defaults) and implement in v1_21_11 + v26_3 + sandbox FakeGame:
player vitals (health, absorption, food, saturation, air, xp), pitch, velocity, light level, world time/day, min/max build height,
boss bars, scoreboard sidebar, title/subtitle, tab-list players, nearby entity details (type, pos, health, hostile, fuse),
block target details (pos, hardness, tool, face), screen kind / F3 / chat open, window focus, TPS estimate, packet stats,
render diagnostics (entity counts, chunks), server brand. Events: chat message, screenshot, block break/place, kill, death, world join/leave.

## Why
HUD, PvP, survival and chat mods need this data.

## Acceptance
Both adapters compile (`./gradlew.bat build`), unit tests pass.
