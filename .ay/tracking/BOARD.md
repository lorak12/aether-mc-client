# Pipeline Board

| # | Title | Agent | Status | Blocked By |
|---|-------|-------|--------|------------|

## Goal: Make the client work in game with the already implemented mods
| 1 | Fabric 1.21.11 adapter project that builds and bootstraps the mods | goal-runner | DONE | - |
| 2 | GameState, UiRenderer, HUD/tick hooks and input events in game | goal-runner | DONE | 1 |
| 3 | Apply Overrides in game | goal-runner | DONE | 2 |
| 4 | In-game mod menu + HUD layout editor | goal-runner | DONE | 2 |
| 5 | Launcher installs Kite jar into Fabric 1.21.11 profiles | goal-runner | DONE | 1 |
| 6 | Verify in a real game session and update docs | goal-runner | DONE | 2,3,4,5 |
| 7 | v26_3 adapter for the 26.3 Fabric profile | goal-runner | DONE | 1-4 |

## Goal: implement every mod from docs/LIST_OF_MODS.md
| 8 | Core platform: profiles, HUD options, manager/keybind pages, client API, dev tools | goal-runner | DONE | - |
| 9 | Expand GameState/Events in both adapters | goal-runner | DONE | - |
| 10 | HUD and information mods | goal-runner | DONE | 9 |
| 11 | Rendering, camera and performance mods | goal-runner | DONE | 9 |
| 12 | World overlays and maps | goal-runner | DONE | 9 |
| 13 | Chat and social mods | goal-runner | DONE | 9 |
| 15 | Tooltips, container search, screenshots, auto tool, server API, crash reporter | goal-runner | IN PROGRESS | 9 |
| 16 | Resource packs, shaders, mod compat, language, performance stack | goal-runner | READY | 9 |
| 17 | Item encyclopedia, recipe viewer, build info, schematic viewer | goal-runner | READY | 9 |
| 18 | Skin layers, emotes, Better GUI (cosmetics done in 20) | goal-runner | READY | 9 |
| 19 | Recording mode, clips, camera paths, music, server browser | goal-runner | READY | 9 |
| 14 | Build verification, coverage docs, manual checklist | goal-runner | READY | 8-13,15-19 |

## Cosmetics (user request)
| 20 | Cosmetics: HD/animated capes, wings, hats, bandanas, ears, face, boots, back; client badge; menu page; starter set; backend sync | claude-cosmetics | DONE | - |

## Launcher UI redesign (user request, spec: docs/superpowers/specs/2026-10-07-launcher-ui-redesign-design.md)
| 21 | Launcher UI redesign: shell, Home, Profiles, Content, Explore, Community, News, Store, coin shop (mock data where no backend) | claude-launcher-ui | DONE | - |

## In-game UI overhaul (user request, spec: docs/superpowers/specs/2026-10-08-ingame-ui-overhaul-design.md)
| 22 | Custom main menu, Right Shift hub, mod window (tabs, profiles sidebar, list/card layouts, settings page with 8 input types), per-file profiles | claude-ingame-ui | DONE | - |

## In-game UI polish (user request)
| 23 | Mod icon set, colour picker, mod removals, skin retry, main menu tweaks, live account switch, carousel fix, UI sounds | claude-ui-polish | DONE | - |

## Finish the launcher (user request, checklist: docs/LAUNCHER_TODO.md)
| 24 | Every item in docs/LAUNCHER_TODO.md: real P0 actions, local profile data, launch options, Forge/NeoForge, CurseForge, backend social/store/feeds (SQLite, Tebex), updater, cleanup, UI/UX polish | claude-launcher-finish | DONE | - |

## Feedback round (user request: FPS/CPS/Ping/compass, HUD editor, launcher skin/logs, wardrobe sync, option cleanup)
| 25 | Colour picker saved colours + 26.3 text input; FPS/CPS/Ping/Coords/Memory label toggles and option cuts; compass clip fix; HUD editor snapping + corner/side resize + arrow nudge; launcher real skin heads + launch/action/failure logging; two-way wardrobe sync; duplicate options removed, missing ones added | claude-feedback | DONE | - |

## Multi-version client (user request: every major version 1.7.10-1.16, 1.18.2-26.3)
| 26 | Adapters for 1.7.10, 1.8.9, 1.12.2 (Legacy Fabric), 1.16.5, 1.18.2, 1.19.4, 1.20.1/.4/.6, 1.21.1/.3/.4/.5/.8/.10, 26.1.2, 26.2; launcher Legacy Fabric loader + version table | claude-multiversion | DONE | - |

## Feedback round 2 (user request: same list as 25 + menu key dead on 26.2)
| 27 | 26.2 input fix (SDL translation was applied to GLFW 26.1/26.2: menu key, clicks, typing); real text fields (caret, selection, click/drag/double-click, arrows, Home/End, Ctrl word jumps, Ctrl+A/C/X/V); launcher Microsoft session cache + single-flight backend sign-in (429 from login_with_xbox broke backend calls / wardrobe sync) | claude-feedback-2 | DONE | - |

## Shop, wardrobe, importer (user request)
| 28 | In-game shop (coins), wardrobe UI rework, admin list (nakii14 = unlimited coins), cosmetic importer (image/GIF capes, OBJ from Blender, in-game Import) | claude-shop | DONE | - |
| 29 | Wardrobe feedback: preview drag fixed + tilt, Yours / Get in the shop lists with buy popup, admin-only importer editor (Blockbench .bbmodel, name/price/category/position/animation, publish to store), creator kit, guide (docs/COSMETICS_GUIDE.md) | claude-shop | DONE | 28 |

## Feedback round 3 (user request: 10 in-game bugs/features)
| 30 | Account switcher skin faces, crisp logo + Kite badge glyph, crisp icons, shop preview without a world; shape-accurate Block Overlay; Item Physics 3D blocks/thick items resting on ground; stable wavy cape sim + no cape with elytra; pixel-grid custom crosshair; canonical settings order + dependent settings; unbindable keys with menu-key fallback (Ctrl+RShift) | claude-feedback-3 | DONE | - |

## Rename Aether to Kite (user request)
| 31 | Every Aether name renamed to Kite (packages gg.kite, mod id kite, KITE_* env vars, config/kite, gg.kite.launcher, cape-kite); pre-1.21.11 "Λ" badge and cape emblem replaced by the kite mark | claude-rename | DONE | - |
