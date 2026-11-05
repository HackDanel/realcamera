# Stage 1 — "digital from shot to frame" (2026-11-05)

Playable: Sony α7 IV + FE 28-70 OSS / FE 85 F1.8, NP-FZ100, SD/CFexpress cards, viewfinder with full HUD,
Auto/P/A/S/M, metering, white balance, AF-S/AF-C/MF, development pipeline (exposure, depth of field from a
ray-cast depth map, vignetting, noise, colour profiles), JPEG + EXIF export, server storage with quotas and
rate limits, card listings, downloads with a client texture cache, printer, prints (BEWLR), wall frames
1x1–4x3 in 8 materials, photo workshop with 103 recipes, battery charger, /photo commands, ru/en.

Verified by `./gradlew test` (22 unit tests: optics, exposure program, pipeline, EXIF) and the end-to-end
`./gradlew runClientAutotest` (all PASS, with and without Iris + Complementary).

## Findings worth remembering

- Mod GUI layers keep rendering when `hideGui` is set. The viewfinder HUD must skip itself while a frame is
  captured, otherwise its mask and exposure preview end up in the photo.
- `BakedModelWrapper.applyTransform` returns the wrapped model, which drops extra render passes; the
  camera+lens composite overrides it to return itself.
- Machine menus must not need the block entity on the client: the open-screen packet can arrive before the
  block update (found by a player clicking around).
- Block selection outline is cancelled in the viewfinder and during capture.

## Not done in stage 1 (moved on)

- `/photo view` and `/photo quota` commands.
- Dedicated-server and two-client multiplayer run (server needs the EULA accepted by the owner).
