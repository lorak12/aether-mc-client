# 21 — Launcher UI redesign

## What
Implement docs/superpowers/specs/2026-10-07-launcher-ui-redesign-design.md in launcher/ui (+ small Tauri helpers).

## Scope
launcher/ui/**, launcher/src-tauri/** (helper commands, window config, capabilities only).

## Acceptance
`npm run build` and `npm test` pass in launcher/ui; `cargo check` passes for src-tauri; every page verified in a browser via devMock.
