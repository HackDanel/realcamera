# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

RealCamera: a realistic-photography mod for **Minecraft 1.21.1 / NeoForge** (ModDevGradle, Java 21, Mojang
mappings + Parchment). Private build only: it uses real camera brands and model names, so it must not be
published. The owner is not a programmer; Claude writes all code and assets. The full feature plan is in
`~/.claude/plans/mellow-munching-sonnet.md`; what each finished stage delivered (and lessons learned) is in
`docs/stage*.md`. The README (Russian) has the player-facing controls.

## Commands

The system Java may be newer than 21; Gradle downloads JDK 21 itself (foojay toolchain).

- `./gradlew build` — jar in `build/libs/`
- `./gradlew test` — JUnit tests (pure-Java optics and image pipeline, catalog consistency, analysis records); they run
  in the NeoForge unit-test environment, the project dir comes in as `realcamera.projectDir`
- single test: `./gradlew test --tests 'dev.hackdanel.realcamera.processing.ImagePipelineTest'` (append `.methodName` for one method)
- `./gradlew runClient` / `runClient2` (second client, nick Player2) / `runServer` (the owner must set `eula=true` in `run/eula.txt` themselves — do not do it for them)
- `./gradlew runClientAutotest -Pautotest=stage1|stage2|stage3|stage4|stage5|stage6|stage7` — unattended end-to-end run in `run-autotest/`: creates a world, builds a scene, shoots, prints, etc. Results are `[autotest] PASS/FAIL` log lines; screenshots go to `run-autotest/screenshots/autotest/`, photos to `run-autotest/screenshots/realcamera/<date>/`. Check results visually by reading those images. The autotest window must not be touched while it runs.
- `-PcaptureWarmup=N` changes warm-up frames for capture in the autotest run; `-PnoShaders` skips copying Sodium/Iris into run dirs. Iris shaders on/off for the autotest: `run-autotest/config/iris.properties`.

Generators (run from the repo root; their output is overwritten every run, so edit the generator, not the output):
- `python3 tools/gen_assets.py` — all textures, item/block models, blockstates, sounds (`.ogg` via ffmpeg)
- `python3 tools/gen_recipes.py` — everything in `src/main/resources/data/realcamera/recipe/` and block loot tables
- `python3 tools/gen_settings.py` — `component/CameraSettings.java` (record, codecs, `withX` methods)
- `python3 tools/gen_catalog.py` — the stage 6 catalog in `tools/catalog.py` (bodies, lenses, mounts, adapters,
  teleconverters, batteries, cards) → registry JSON + en/ru names. `gen_assets.py` and `gen_recipes.py` read the same
  file for models (body `style`, lens `look`) and recipes (tier/kind). Earlier-stage entries are hand-written JSON.
- `python3 tools/gen_loot.py` — structure loot (`realcamera:add_items` global loot modifiers)
- `python3 tools/gen_advancements.py` — the advancement tab + its en/ru names
Run order after editing the catalog: gen_catalog → gen_assets → gen_recipes. `CatalogTest` checks references, flange
rule for adapters, models, names and recipes.

## Architecture

**Data-driven catalog.** Bodies, lenses, mounts, adapters, teleconverters, batteries, cards, flashes,
filters, grips and colour profiles are datapack registries (`registry/RCRegistries`, records in `spec/`),
loaded from `data/realcamera/realcamera/<registry>/*.json` and synced to clients. Items are generic
(`camera_body`, `lens`, …); the concrete model is a `ResourceKey` data component (`registry/RCComponents`).
Adding equipment = an entry in `tools/catalog.py` (or JSON + model + lang keys `realcamera.<registry>.<ns>.<path>` +
recipe by hand). Compact cameras: the body names a `fixed_lens` on the pseudo-mount `realcamera:fixed`; the body
item fills its lens slot itself and the parts menu locks it.
Mojang record codecs allow at most 16 fields per group: use nested records (see `CameraBodySpec`) or the
settings generator.

**Optics always go through `camera/OpticalChain`** (body → adapter → teleconverter → lens, effective sensor
with APS-C crop/image circle, effective focal/aperture, AF speed, EXIF lens name). Camera parts live in the
`CameraParts` component (8 slots); wear in `CameraWear`.

**Common vs client.** Client-only code is under `client/` (loaded via `@Mod(dist = CLIENT)` and
`@EventBusSubscriber(value = Dist.CLIENT)`). Common code reaches the client only through `ClientBridge`
(implemented by `client/ClientBridgeImpl`); server→client payloads are registered in common code and
dispatched via `ClientBridge.handlePayload`.

**Photo pipeline (end to end).**
1. `client/camera/Viewfinder` — camera at the eye (or on a tripod): FOV from focal length, metering
   (`SceneMeter` converts game light to EV), `optics/Exposure.solve` (P/A/S/M), AF/subject detection,
   drive modes, shutter. Composes a `PhotoCapture.Shot`.
2. `client/capture/PhotoCapture` — renders one clean frame at sensor resolution by temporarily resizing the
   window/main render target (one warm-up frame first), reads it back, ray-casts depth and light grids
   (`CameraRays`); exposures ≥ 1/4 s accumulate every frame through PBOs. Development runs on a worker thread.
3. `processing/ImagePipeline` + `ImageParams` — pure Java (no Minecraft classes, unit-tested): exposure,
   DoF/bokeh, flash, motion blur, rolling shutter, distortion/CA, vignetting, wear, flare, noise, colour.
   `optics/` is pure Java too. Keep both packages free of game classes. Special optics: fisheyes render a capped
   wide rectilinear frame (`Optics.renderVfovDeg`) and `ImagePipeline.fisheye` remaps it; a shifted tilt-shift lens
   renders the frame pitched up by atan(shift/f) (`ComputeCameraAngles`) and `lensShift` re-projects it; tilt
   changes the focus distance per row (`Optics.tiltedFocusM`); bokeh styles smooth/busy/swirl/bubble.
4. Export: full-size JPEG with EXIF (`processing/ExifWriter`) and optional RAW to `screenshots/realcamera/`.
   RAW = output of `ImagePipeline.developSensor` (before `finish`: roll-off, WB, profile) as a 16-bit linear PNG +
   JSON sidecar (`processing/RawFile`); `client/photo/LocalOriginals` remembers which local files belong to which
   photo id. A downscaled JPEG goes through `client/photo/PhotoUploader` (sequential queue; camera shots and editor
   saves share it).
5. Server: `network/ServerPhotoService` validates (quota, rate, JPEG header), stores files under
   `<world>/realcamera/photos/`, indexes them in SavedData (`photo/PhotoIndex`, `photo/CardStorage` — card
   contents live on the server, the card item only mirrors usage), streams downloads with a bandwidth budget.
6. Film: a film body (`CameraBodySpec.film`) takes a `FilmRoll` in the card slot; frames are uploaded as latent
   index entries keyed by the roll id (like a card) and only become visible after `photo/FilmLab.developRoll`
   (developing tank). Darkroom light checks: `gameplay/Darkroom`.
7. The computer's editor (`processing/PhotoEditor`, pure Java; `client/editor/EditSession` loads the local
   original or the in-game copy and renders previews on a worker) saves results as new photos
   (`PhotoMeta.editedFrom`). Files are removed only when no card references them and they were never printed.
8. Clients show photos via `client/photo/PhotoCache` (LRU GL textures + per-server disk cache): prints
   (`PrintItemRenderer` BEWLR), wall frames (`PhotoFrameRenderer`), playback/printer screens.

9. Game goals: the `Shutter` packet carries a `ShotInfo` (nonce); `analysis/FrameAnalyzer` analyses the frame on the
   server from its own world state and the result waits by nonce for the upload (`PhotoMeta.analysis`).
   `gameplay/PhotoEvents.photoStored` (digital: on store; film: on development) feeds the encyclopedia
   (`gameplay/Journal`, player attachment, synced with `JournalSync`) and the advancement triggers
   (`gameplay/RCTriggers`); other deeds go through `PhotoEvents.count/event`. Newspaper orders: `component/Commission`
   on a trade cost, matched by `mixin/ItemCostMixin` (the only mixin).

Network messages identify a camera with `camera/CameraRef` (a hand or a tripod entity id); the server
resolves it with `ServerPhotoService.resolve`, mutates the stack and must call the handle's `commit()`
(tripod cameras are copies stored in entity data).

**Item models.** `client/render/SpecItemModels` picks the model named in the spec JSON and draws a camera
as several render passes (body + `_mounted` adapter/teleconverter/lens models, shifted along the optical
axis; 1 model unit ≈ 9 mm, shared mount point in `tools/gen_assets.py`).

## Gotchas (all hit before)

- Mod GUI layers still render when `options.hideGui` is set: the viewfinder HUD must skip itself while
  `PhotoCapture.capturing()`, or it ends up in the photo.
- Resizing the render target needs a warm-up frame; the block selection outline is cancelled in the
  viewfinder and during capture.
- `BakedModelWrapper.applyTransform` returns the wrapped model and drops extra render passes — composites
  override it to return `this`.
- Machine menus must not depend on the client block entity (the open-screen packet can arrive first).
- Sodium 0.8 is jar-in-jar and breaks on the dev classpath: Sodium/Iris come from the `devMods`
  configuration copied into each run dir's `mods/`.
- A record component cannot be named `wait` (clashes with `Object.wait()`).
- Model elements must stay inside -16..32 and rotate only by ±22.5/45° on one axis: `cylinder()` in gen_assets builds a
  16-sided prism from 8 rotated spokes; very long lenses are shortened to 24.5 units.
- The depth map comes from ray casts against entity bounding boxes, not the rendered model: wide-open portraits get a
  sharp halo where the box is wider than the model. Tiny light sources get the blur radius of their surroundings.
