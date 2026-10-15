# Multi-version in-game checklist

Every adapter below builds, passes `runtime/tools/mixin_check.py` and boots through `runAudit` (all mixin targets load, game quits). That does **not** prove behaviour, so check these in game. Use `cd runtime && ./gradlew.bat :<adapter>:runClient` (legacy: `-p legacy :v1_8_9:runClient`) or install the jar through the launcher. `runAutotest` also exists for versions that already had it.

Versions: 1.7.10, 1.8.9, 1.12.2, 1.16.5, 1.17.1, 1.18.2, 1.19, 1.19.2, 1.19.3, 1.19.4, 1.20, 1.20.1-1.20.6, 1.21, 1.21.1, 1.21.3-1.21.11, 26.1, 26.1.1, 26.1.2, 26.2, 26.3.

## Every version
1. Title screen is the Aether menu (vanilla button "Aether menu" brings it back from the vanilla title screen). Right Shift opens the hub in a world and in a menu.
2. HUD: FPS, CPS, coordinates, armor, potions, keystrokes draw in the right place; HUD editor drags/scales.
3. Zoom (C, scroll changes the level), freelook (V), fullbright (G); settings in options.txt are not changed afterwards (check `options.txt` for gamma / fov after quitting).
4. Wardrobe: icons show, preview turns, equip a hat, a back item and a cape: they appear on your player in third person (F5) and on other players that run the client.
5. Wavy capes on/off; a cosmetic cape with and without a Mojang cape.
6. Badge before client users' names (nametag and tab list). 1.21.11+ draws the Kite mark; older versions draw a coloured "Λ".
7. Chat: timestamps, "(xN)" stacking, tabs, hide chat, name replacement (Streamer mode) and the server channel `aether:client` (a plugin that sends HUD lines).

## Versions before 1.21.2 (no render state: new code paths)
- Item Physics on/off (items lie flat, stack layers), item bob / spin / scale, hide item shadows.
- Hide sun / moon / stars (Sky mod).
- "No equip animation", hide fire / water overlay, swing speed (Animation Tweaks).
- Mob highlight, nametag health and distance limit.
- Sidebar, boss bar, title, tab header / footer in the HUD mods.

## 1.7.10 / 1.8.9 / 1.12.2 (Legacy Fabric, immediate-mode GL)
- Cosmetics: models sit on the right bones (head, body, arms, legs), glow parts stay bright at night, translucent ones blend. Cape follows movement. Hurt flash tints cosmetics too.
- Tooltips mod extra lines and colours; Container Search (type in the box, Esc/Enter leaves it, letters do not move items).
- Hit colour, hidden entities (1.12.2 also chunk borders F3+G), hitboxes via the Hitboxes mod.
- 1.7.10 has no layers: cosmetics are drawn from the player renderer's special-render pass. Not available there: tab-list names and tab hide, own nametag in third person, item physics, title (1.7.10 has none), boss bar is the single vanilla bar.
- Not done in the legacy tier: mob highlight outlines, particle filter on 1.7.10, block break / place / pickup events.

## 1.16.5 / 1.17.1 / 1.18.2 / 1.19 / 1.19.2 (new GUI bridge)
- Menus draw through `PoseStack`: check text, icons and clipping (scrolling lists) in the main menu, hub, wardrobe and HUD editor.
- 1.16.5: world tinting and blending after the HUD pass (no leftover colour on the hand or sky), item icons in the HUD.
- 1.18.2 and older: option overrides (FOV, particles, clouds, render distance, gamma) apply and are restored; volume scale from Streamer/Recording mode works.
- 1.19: chat "(xN)" and tabs work; no chat message tags there (1.19.1+ has them).
