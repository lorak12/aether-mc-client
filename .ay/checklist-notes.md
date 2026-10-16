# In-game checklist notes (compiled into docs/MANUAL_TEST_CHECKLIST.md at the end)

## Task 8 (platform)
- Menu: favorites star, filter chips (All/★/On/Configurable/Recent), Reset all (confirm), sidebar Profiles/Keybinds/Developer.
- Profiles: + New, rename, Auto servers field, Use, Delete, Export -> config/kite/export.json, Import from config/kite/import.json.
- Auto-switch: set "Auto" of a profile to a server address / "singleplayer", join -> toast "Profile: X".
- Keybinds page: rebind menu key, conflicts highlighted; holding the menu key must not close the menu instantly.
- HUD settings rows: Hide while F3 / chat / menus.
- Notifications mod (corner, duration, DND), Accessibility mod (menu scale, high contrast, reduced motion, colorblind).

## Task 10 (HUD)
- FPS stats/graph/color; Ping avg/min/max/jitter/graph/TPS; CPS middle/total/best/window/graph.
- Keystrokes: size, spacing, radius, fade, Shift/Ctrl row, CPS in buttons.
- Armor Status: held/offhand, names, enchantments, bar, horizontal, warning color.
- Potions: sort, hide negative/infinite/list, bar, warn color, hide vanilla icons.
- Coordinates: chunk/local/facing/rotation/biome/dimension/light/nether coords. Direction: compass strip.
- Clock: MC time/day/date/timezone. Memory: %, GC, graph.
- Boss Bar / Titles / Scoreboard replacements (vanilla hidden, draggable); test on a server with a boss bar/sidebar (/title in SP).
- Hotbar replacement (vanilla hidden): size/spacing/colors/slide.
- Food, Item Durability (+break warning), Height Limit, Biome/Structure (structure in SP), WAILA (tool/harvest/mod/icon).
- Playtime (persisted; restart game and check totals), Statistics (mine/place/jump/kill; reset key), Achievement Tracker.
- Network Monitor, Debug HUD sections, Rendering Diagnostics, Server Status (on a server).
- Health Display, Damage Indicator, TNT Timer (prime TNT), PvP Dashboard, Combo pulse, Reach max/graph.
- Hardcore, Survival, BedWars (Hypixel), SkyBlock (Hypixel) dashboards.

## Task 11 (rendering / camera / performance)
- FOV: custom base, dynamic FOV off/strength, sprint/fly/water/bow multipliers; options.txt must keep your real FOV after quitting.
- Camera: bobbing, hurt cam %, nausea %, darkness %, fire overlay lowered/hidden, water overlay, smoothing, 3rd-person distance, no clip.
- Animations: item switch animation off, swing speed, glint strength/speed.
- Sky (stars/sun/moon per dimension), Clouds (mode, distance).
- Entity Rendering: distance %, shadows, hide armor stands/frames/paintings/items/xp/falling blocks/nametags.
- Particles: vanilla amount, multiplier %, block/potion/explosion/rain/damage filters, extra crits, always sharpness particles.
- Hit Color (hurt flash color), Item Physics (flat, spin, bob, size, shadow), Mob Highlight (glow colors, distance; blocked in competitive mode).
- Nametags: health, own nametag in F5, max distance, hide NPCs. Hitboxes (toggle key). Menu Blur.
- Cinematic Camera (F9): smooth cam, no HUD/hand, FOV, time lock.
- FPS Limiter (fixed/monitor/menus/unfocused/AFK), Dynamic FPS (unfocused FPS + volume), FPS Profiles presets (render distance etc. revert when disabled).
- Zoom: hide HUD, smooth camera, zoomed sensitivity. Fullbright: caves only, dimension/world lists. Freelook: split sensitivity, max yaw, smoothing.
- Weather: intensity, rain volume. Toggle Sprint: sprint with inventory open, always sneak. Accessibility: reduced flashing/particles, no nausea.

## Task 12 (world overlays and maps)
- Chunk Borders: custom lines (current/neighbour chunks, subchunks, grid), slime chunks (SP), chunk label; Vanilla style = F3+G.
- Light Overlay (F10 toggle): crosses/numbers/squares on spawnable blocks; red = always, yellow = at night.
- Waypoints: B adds one; edit/delete in the mod's settings table (name, x, y, z, dimension, color); beams, labels with distance,
  through walls, death waypoint + auto-removal, nearest-waypoints HUD with arrows; per world (switch worlds and check).
- Block Overlay: custom outline/fill/face, vanilla outline hidden. Block Selection Helper: preview cube while holding a block.
- Minimap: size, zoom, circle, rotate, cave map (auto underground), entity dots, waypoints, coords; fair-play mode.
- World Map (M): drag, scroll zoom, right-click waypoint, C centre, E export PNG (config/kite/data/worldmap), dimension tabs, search / "x z" jump.
  Explored map persists across restarts (config/kite/data/minimap or worldmap).
- TNT Timer floating text, Damage Indicator floating numbers, Hitboxes custom (colors per category, fill, through walls), Height Limit plane.

## Task 13 (chat and social)
- Chat: timestamps (24h/seconds), mention highlight bar + ding (have someone say your name, or /say in SP), extra mention words,
  "(x2)" stacking of repeated lines (e.g. spam /say same text), hide regex (add a pattern, matching lines vanish), highlight regex
  with color, ignored players (their lines vanish), history 500+ (scroll far back), hide chat (still visible while typing).
- Chat tabs: set "Chat tab" or bind "Next chat tab key"; while typing the tab strip shows bottom-right; Party/Guild/Whispers/System filter
  the visible chat and switching back to All restores everything.
- Chat history view: bind "Search chat history key", type to search, click a line = copied to clipboard, hidden lines greyed.
- Chat Notifications: /msg yourself from a 2nd account or Hypixel PM/party invite -> sound + toast; keywords/players tables; desktop toast when unfocused.
- Tab List (hold Tab): heads, ping numbers/bars/colors, sort by name/ping/team, self + friend highlight, header/footer toggles, rows per column, move/scale in HUD editor.
- Friends: add via table or "Add the player you look at" key; nickname appears in chat/tab/nametag; join/leave toast; HUD list; offline list with last seen.
- Streamer Mode: your name shown as "Streamer" in chat, nametags (F5) and tab; custom name table; hide chat / vanilla toasts; friends HUD hidden.
- Recording Mode: advancement toasts hidden, Kite toasts hidden, name shown as "Player", server IP/coords HUDs hidden.

## Task 15 (partial: inventory, capture, server API)
- Sounds / clipboard / desktop notifications now work in-game (McHost): mention ding, history-view copy.
- Item Information: tooltip lines (durability, food, id, mod name; tags/components with F3+H), custom tooltip colors.
- Container Search: box above chests/inventory; type (E must not close), @mod #tag $enchant -exclude; right-click clears.
- Auto Tool: hold attack on a block -> best hotbar tool, switches back after release; weapon option.
- Screenshot Manager: F2 then check rename pattern / per-world folder / copy / open / toast; "Open gallery" button in settings.
- Crash Reporter: after a crash, toast on next start; "View" shows redacted summary, Copy/Save/Open original.
- Server Integration: needs a server plugin sending kite:client JSON (see ServerApi javadoc); leave server -> cleared.

## Task 20 (cosmetics)
- Right Shift > Cosmetics (header button and sidebar row). Preview shows you; drag turns it 360°; capes/wings/back tabs start from behind.
- Hover a card: the preview tries it on; leave the grid: back to your loadout. Click equips (✓), click again / right-click / None removes.
- Capes: Kite, Midnight (static 512x256), Aurora, Ember, Galaxy (animated) in F5 from behind; cape physics while running; glide with an elytra: it uses the cape texture.
- Wings: Angel / Dragon / Fairy flap slowly standing, harder while flying (creative double-jump) or gliding; hidden while wearing an elytra.
- Hats: Top Hat, Crown (gems glow in the dark), Halo (floats, bobs, turns, full-bright), Red/Blue Bandana (tails sway more when sprinting).
- Ears (Cat, Bunny), Face (Shades), Boots (Winged: little wings flap when running; Frost: glowing crystals), Back (Explorer Pack).
- Hide under armor: helmet hides hats/ears, boots hide boots; toggle off shows them anyway. Invisibility hides everything.
- Toggles: Cosmetics (all off, including yours), Other players, Client badge. Menu thumbnails animate for animated capes.
- Wavy capes (toggle, on by default): in F5 from the side and behind, standing still the cape sways gently; walking it trails back, sprinting further with a fluttering hem; jumping/falling lifts it; turning swings it sideways. Also on a Mojang cape and on other players' capes. Chestplate: cape sits out over it, not clipping. Elytra: no cape. Toggle off: vanilla stiff cape again. Menu preview sways too.
- Badge: "Λ" before your name in the tab list and (F5 / other clients) above your head; off with the Client badge toggle.
- Own cosmetics: Open folder, drop a 64x32/512x256 PNG (or a vertical frame strip) in, Reload: it shows under Capes as Local. A broken folder shows a warning line.
- Inventory screen player also wears cosmetics. Restart: loadout and toggles are kept (config.json "cosmetics").
- Backend (optional): run backend, set backend_url in kite.config.json, sign in with Microsoft, Play: status line says Connected; a second client user nearby shows your cosmetics + badge. Offline account: status says it could not verify the account.
