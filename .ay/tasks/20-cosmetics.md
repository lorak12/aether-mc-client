# 20 — Cosmetics and client badge

## What
Data-driven cosmetics (cosmetic.json + PNG, bundled starters + config/aether/cosmetics): high-resolution and animated
(vertical frame strip) capes through vanilla's cape layer (elytra too), box-UV models (wings, hats, bandanas, cat ears,
face, boots, back) drawn by a player render layer with simple animations (flap, bob, spin, glow). "Λ" badge before
the names of client users (nametag + tab list). Cosmetics page in the Right Shift menu with a rotatable player preview,
hover try-on, click to equip, show/hide toggles. Backend sync (Mojang-verified login, loadout, presence lookup) so
other client users see your badge and cosmetics; the launcher passes the backend URL.

## Acceptance
Unit tests: model parsing, box-UV mesh matches vanilla ModelPart.Cube, mirroring, animation, registry loads every
starter, loadout persistence, backend sync against a fake server. Both adapters compile. Backend tests pass.
Supersedes the cosmetics part of task 18.
