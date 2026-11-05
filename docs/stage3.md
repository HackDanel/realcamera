# Stage 3 — accessories and wear (2026-11-05)

## What was added

- **Camera slots** (`component/CameraParts`, 8): lens, adapter, teleconverter, filter, flash, battery, grip, card.
  New datapack registries `flash_type`, `filter_type`, `grip_type`; body specs gained `flash` (built-in guide
  number, sync speed).
- **Flash**: hot-shoe speedlights (Sony, Nikon, Canon, Fujifilm, Godox) and built-in flashes. Modes off / fill /
  rear curtain / auto (V key or camera menu), TTL power from guide number, ISO, aperture and focus distance,
  ±2 EV compensation, recycle time, battery use, others see the pop (particle). In the picture the light falls off
  with 1/d² over the ray-cast depth map and is applied to surface colour (pixel / light level from a light grid).
  Above the sync speed the second curtain shades the lower part of the frame unless the flash has HSS (then it is
  much weaker); leaf shutters sync at any speed; no flash with the electronic shutter.
- **Filters**: ND8/64/1000 (the camera meters through them, so ND1000 forces long exposures in daylight), circular
  polariser (deeper, more saturated sky), UV (takes the hit in a fall), yellow/orange/red contrast filters for black
  and white. A filter fits if its thread is at least the lens thread (step-up ring assumed).
- **Battery grip**: second charge used before the body battery; batteries, grips and flashes all charge in the charger.
- **Tripod** (entity): mount a camera with right click, look through it with an empty hand (mouse pans/tilts the head,
  no hand shake, walking blocked, sneak to step back), take the camera back with sneak + right click. Self-timer keeps
  running after stepping back and the capture renders from the tripod, so the photographer appears in the picture.
  Network messages identify cameras with `camera/CameraRef` (hand or tripod entity).
- **Wear**: mechanical shutter jams with growing probability past its rated life (electronic shutter still works);
  moisture from rain (in hand) and water (anywhere) by weather sealing — fogging above 40, dead electronics at 100 for
  unsealed bodies; falls ≥ 4 damage crack the UV filter or knock the lens (one side soft); lens changes in deserts,
  badlands, the Nether, beaches or rain can put dust on the sensor (visible from about f/8); cold biomes drain
  batteries 1.5× faster.
- **Repairs** in the workshop (`repair` recipes keep the item's components): new shutter unit (resets the count),
  electronics, lens, filter. Cleaning kit removes dust and dries the camera. Camera bag holds 12 pieces of gear.

## Verified

`./gradlew test` (33 unit tests incl. flash fall-off, curtain shadow, dust visibility, red filter) and
`./gradlew runClientAutotest -Pautotest=stage3`: polariser, red filter B&W, ND1000 long exposure, fog + dust +
knocked lens, jammed shutter refused / electronic works, workshop repair keeps parts, grip drained first, tripod
self-timer selfie, flash at night, built-in flash above sync speed (shaded frame), camera dies in water, bag screen.
