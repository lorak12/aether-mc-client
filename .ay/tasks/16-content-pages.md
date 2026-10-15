# 16 — Resource packs, shaders, mod compatibility, language, performance stack

## What
Resource Pack Manager page (installed packs, search, enable/disable, priority order, favorites, per-profile pack lists, reload),
Shader Manager (Iris via reflection when present: list packs, enable/disable, reload, per-profile shader; warning when missing),
Mod Compatibility page (loaded Fabric mods with known-compatible / conflict / unsupported table), Language (switch Minecraft
language from the menu), launcher "Performance mods" option that installs Sodium/Lithium/FerriteCore/ImmediatelyFast/
EntityCulling/ModernFix from Modrinth for Fabric profiles (§53).

## Acceptance
Unit tests for compat table / pack ordering; launcher cargo tests pass; adapters compile.
