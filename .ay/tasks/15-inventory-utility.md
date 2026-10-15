# 15 — Tooltips, container search, screenshots, auto tool, server API, crash reporter

## What
Tooltips / Item Information (ids, components, tags, durability, food, compact; Events.Tooltip in adapters), Container Search
(search box in container screens, dims non-matching slots), Screenshot Manager (naming pattern, clipboard copy, toast, open folder)
+ Screenshot Gallery view (thumbnails, sort, favorites, delete, copy, open), Auto Tool (best tool for targeted block, weapon on
attack, restores slot; Overrides.selectSlot in adapters), Server-specific API (custom payload channel `aether:client` with JSON
HUD lines / waypoints / module toggles), Crash Reporter (writes a privacy-safe summary on crash, menu page to view last one).

## Acceptance
Unit tests for parsers (tooltip formatting, search matching, screenshot naming, tool scoring, payload parsing, crash summary);
adapters compile.
