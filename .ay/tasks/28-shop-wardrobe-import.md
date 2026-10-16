# 28 — In-game shop, wardrobe rework, admin coins, cosmetic importer

## What
- In-game Shop page (hub tile, main menu Store button / coin pill, wardrobe header): backend store items with
  prices, try-on preview, buy with launcher coins (POST /v1/store/buy), equip after buying.
- Wardrobe UI rework: coin balance + Shop button, locked items show price and lead to the shop, search,
  selected-item detail bar, Import button.
- Admin list (backend/src/admins.ts, default "nakii14", extra names via KITE_ADMINS): unlimited coins, buying
  grants without charging; /v1/me, /v1/store and buy report `unlimited`. Launcher shows the balance as ∞.
- Cosmetic importer: any PNG/JPG/GIF becomes a cape (GIF = animated), GIF/JPG model textures, OBJ models from
  Blender/Blockbench (objects named after bones, pivots + animate per group), Blender kit (player reference OBJ),
  in-game Import dialog with a native file picker (tinyfd; AWT on legacy).

## Acceptance
Unit tests: image/GIF -> cape conversion, OBJ parsing/meshing, importer writes a loadable folder, shop state
parsing, backend admin tests. Core/mods tests, backend tests, launcher tsc pass; the 1.21.11 and 26.3 jars build.
In-game checklist handed to the user.
