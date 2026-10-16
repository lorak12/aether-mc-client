# In-game UI overhaul: main menu, Right Shift hub, mod window, profiles

Date: 2026-10-08. Status: approved by the user ("do everything now, you choose the architecture").

## Goal

Replace the in-game UI with a modern, Lunar-style one, in one pass:

1. A custom main menu that replaces vanilla's title screen (and can be turned off).
2. A Right Shift hub (framed screen, logo, Emotes / Mods / Wardrobe).
3. A mod window (Mods / Settings / Waypoints tabs, profile sidebar, two list layouts, a settings page with eight
   input types).
4. Profiles as single, exportable JSON files ("mods.json" per profile). Everything else is client-wide.

## Decisions

### Storage

- `config/kite/config.json` (schema 3) keeps client-wide state: menu key, competitive mode, favorites, mod data,
  cosmetics, the active/manual profile name, seen mods, and `client` (the new `ClientSettings`).
- `config/kite/profiles/<file>.json`: one file per profile. This file is the exportable profile:

  ```json
  { "format": "kite-profile", "version": 1, "name": "PvP", "icon": "minecraft:diamond_sword",
    "servers": "hypixel.net", "mods": { "fps": { "enabled": true, "settings": { ... }, "hud": { ... } } } }
  ```

  Mod ids are ours (lowercase). Mod entries use the same shape as today's snapshot, so the format matches the
  in-memory model.
- Schema 2 configs (profiles inside `config.json`) are migrated on load: each profile is written to its own file.
- Export: copy the file to `config/kite/exports/` and reveal it, or copy the JSON to the clipboard.
  Import: paste JSON from the clipboard, or drop files into `profiles/` (picked up when the menu opens).

### Settings model

- `Setting.Kind`: COLOR_PICKER, TOGGLE, NUMBER_VALUE, TEXT_VALUE, OPTION, SLIDER, KEYBIND, BUTTON (+ TABLE for
  `Entries`). Every setting reports its kind; the UI dispatches on it.
- `Int`/`Dbl` render as SLIDER by default, or NUMBER_VALUE when their range is huge (> 1000 steps) or when the mod
  calls `.number()`.
- HUD mods keep the universal options (position, scale, opacity, background, border, colors, visibility rules).

### Mod catalog

- Tabs: ALL, NEW, HUD, SERVER, MECHANIC. HUD = `Category.HUD`; SERVER = mods flagged server-specific; MECHANIC =
  everything else.
- NEW = mods not in `seenMods`. On first run every mod is marked seen. A mod becomes seen when its settings page
  opens or it is toggled.
- Icons: every mod gets a Minecraft item id as its icon (drawn with `UiRenderer.item`). Profiles also use item icons.
- Search: matches by name first, then description, then setting labels (ranked), with an explicit focus model.

### Rendering

- Fonts: Inter (UI) and Sora (display), both OFL, ship as TTFs in the jar. Mod jars are not resource packs without
  Fabric API, so Minecraft's TTF font providers can't see them. Instead `McFonts` rasterizes each face into a glyph
  atlas with Java2D (5x/10x oversampled, linear filtering) and `GuiRenderer` draws tinted glyph quads.
  `UiRenderer.font(Font)` switches faces; anything outside Latin-1 falls back to the vanilla font.
- Logo: `assets/kite/textures/gui/logo.png` (the brand mark from `brand/icon.png` on a transparent background),
  loaded from the jar into a dynamic texture and drawn with `UiRenderer.texture`.
- Popups call `UiRenderer.layer()` (`nextStratum`) so they cover earlier text.
- Shared assets live in `runtime/mc-shared/src/main/resources`, added to both adapters.

### Views (core, `gg.kite.core.ui`)

- `Ui`: immediate-mode toolkit (hit regions, focus, scrolling, popovers, every widget).
- `ClientGui`: the Right Shift shell. Pages: HUB, MODS (window), WARDROBE, HUD_EDITOR. Its public API
  (`openSettings`, `openPage`, `openHudEditor`, `openedByMenuKey`) is kept for the autotest.
- `ModWindow`: header (logo, "Kite Client", tabs, close), sidebar (profiles + Save as new profile + Import +
  Edit HUD layout; on the Settings tab: General / Keybinds / Developer), body (mod list, mod settings, client
  settings, waypoints).
- `MainMenuView`: account switcher (top left); coins, vanilla-menu toggle and quit (top right); logo,
  Singleplayer, Multiplayer, Store and promo (center); client buttons (bottom center: Client settings, Wardrobe,
  Chat, Settings, Realms, Mod Menu if installed); announcement carousel and disclaimer (bottom right); version
  (bottom left).
- Data without a backend (coins, announcements, promo, store, chat) comes from `MenuFeed`, a clearly labelled
  preview, like the launcher's mock data. Store and Chat open a "coming soon" panel.

### Adapters

- The title screen is swapped for `KiteScreen(MainMenuView)` in `setScreen` (`Minecraft` on 1.21.11, `Gui` on
  26.3) while `customMainMenu` is on. The vanilla title screen gets an "Kite menu" button to switch back.
- The main menu draws the vanilla panorama behind itself (`View.panorama()`).
- `McMenuHost` opens vanilla screens (world select, multiplayer, options, Realms), Mod Menu by reflection when
  `modmenu` is loaded, and quits the game.

### Accounts

- The launcher passes `KITE_ACCOUNTS_FILE` (its `accounts.json`, names/uuids only, no tokens) and
  `KITE_RELAUNCH_FILE`.
- Picking another account in game writes `{uuid}` to the relaunch file and quits. When the game exits, the launcher
  sees the request, switches the active account, and plays the same profile again.
- Without the launcher, the switcher shows only the current account.

## Testing

- Unit (core): profile file round-trip, schema 2 migration, import and name clashes, setting kinds, catalog
  tabs/NEW/search ranking, color picker HSV math, main menu/hub/window rendering against a recording renderer
  (no exceptions, buttons reachable, clicks route to the host).
- Both adapters build (`./gradlew build`). In-game verification is manual (checklist in the handoff).
