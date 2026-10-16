# 13 — Chat and social mods

## What
Chat (timestamps, mention highlight/sound, regex filters, ignore list, anti-spam stacking, tabs, history length, hide),
Chat Notifications (keywords, players, PMs, party invites, friend join/leave, restart/auction/queue patterns), Tab List
(ping numbers, highlight self/friends, hide header/footer, sort), Friends (list, nickname, notes, online/last seen from tab list),
Streamer Mode / Recording Mode extensions (name replacement, hide chat/notifications/skin names).
Adapters: apply ChatReceive (cancel/prefix/highlight/replace/stack), chatFilter + rebuild, chatHistory, hideChat, nameReplacements,
hideVanillaToasts, tab list rendering changes.

## Acceptance
Unit tests for filters/parsers; adapters compile; in-game checks go on the final manual checklist.
