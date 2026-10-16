# 8 — Core platform: profiles, universal HUD options, mod-manager and keybind pages, client API, developer tools

## What
- Profiles: named config profiles, auto-activation by server address, switch/create/delete from the menu (LIST §1, §84, §106 local part).
- Universal HUD options on every HudElement: background/border/shadow overrides, hide in F3 / chat / inventory / F1 (§2).
- Mod manager: favorites, "only enabled", "only configurable", reset category / reset all, import/export config (§1).
- Keybind manager page: every key setting in one searchable list with conflicts (§100).
- Notifications settings: position, duration, do-not-disturb (§101).
- Client API: third-party mods discovered via ServiceLoader (§119). Developer page: event monitor, config inspector, failures (§120).
- Accessibility (§98): reduced motion / high contrast / UI scale options applied by ClientGui and HUD.

## Why
Every later mod relies on the shared HUD/config/profile/keybind systems.

## Deliverables
runtime/core (ModManager, HudElement, ConfigStore, Profiles, ui/ClientGui), tests in core.

## Acceptance
`./gradlew.bat :core:test :mods:test` passes; adapters build; menu pages checked in-game.
