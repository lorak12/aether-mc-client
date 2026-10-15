# 17 — Item encyclopedia, recipe viewer, build information, schematic viewer

## What
Item browser (every registry item, search by name/id/tag/mod, favorites, components), Recipe viewer (recipes known to the client or
the integrated server: crafting/smelting/smithing/stonecutting; recipes + uses, ingredients), Build Information (block counts in
inventory vs required), Schematic viewer (.schem Sponge / .litematic load from config/aether/schematics, move/rotate/mirror,
ghost boxes, missing/wrong/correct highlight, layer view, progress + material list). NBT reader in core.

## Acceptance
Unit tests for NBT/schematic parsing, transforms, material counting, search; adapters compile.
