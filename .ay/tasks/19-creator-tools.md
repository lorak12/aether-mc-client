# 19 — Recording mode, clips, camera paths, music, server browser

## What
Recording Mode (hide notifications/IP/account, disable intrusive UI), Clip system (rolling ffmpeg capture of the game window
when ffmpeg is on PATH: save last 15/30/60/120 s, manual record), Cinematic camera paths (keyframes of position/rotation/FOV,
linear/smooth interpolation, playback with camera override), Now Playing / Spotify (Windows: current song from the Spotify window,
media-key play/pause/next/previous), Server Browser (curated + favorites list, server list ping with MOTD/players/version/icon, join).

## Acceptance
Unit tests for keyframe interpolation, SLP response parsing, clip segment selection; adapters compile.
