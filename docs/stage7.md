# Stage 7 — game goals (2026-11-06)

## What was added

- **Server frame analysis** (`analysis/FrameAnalyzer`, plan §7.1). At the release the client sends a `ShotInfo`
  (nonce, focal, f-number, shutter, ISO, focus distance, exposure error, motion blur) with the `Shutter` packet; the
  server analyses the frame from its own world state: the photographer's or tripod's eye and rotation (minus the
  shift pitch of a tilt-shift lens), the lens's field of view. It finds living entities whose box is in the frame and
  visible (ray casts to five points), their share of the frame, distance, centre and whether they are inside the depth
  of field; lightning in view; share of sky (96 rays); sun/moon in the frame; time, weather, moon phase; the structure
  at the focus point; how it was taken (tripod, film, flash, electronic shutter, fisheye, tilt-shift, adapter, medium
  format, under water); magnification. Phenomena: sunrise, sunset, starry night, star trails (completed at upload
  from the real exposure time, tripod ≥ 15 s), full moon, rain, thunderstorm, snowfall, lightning, End sky, under
  water. The analysis waits (by nonce) for the upload and is stored in `PhotoMeta.analysis`; edited copies and
  enlarger prints keep it.
- **Quality score** (`analysis/QualityScore`, pure Java): exposure 25, sharpness 25 (an out-of-focus subject loses most
  of it), subject size 25, rule of thirds 15, noise 10; landscapes are judged by sky and phenomena. ≥ 75 → ★★★,
  ≥ 50 → ★★. Shown on print tooltips with what is in the picture.
- **Photo encyclopedia** (`gameplay/Journal`, a player attachment that survives death; `client/screen/JournalScreen`):
  J key or the **field journal** item. Tabs creatures (every non-MISC entity type plus villagers, golems, bosses —
  modded ones too), biomes (the synced biome registry), dimensions (sent by the server), phenomena. A page opens with
  the first sharp photo of it and keeps the best one (thumbnail, stars, quality, game day); chat announces new pages.
  Film frames count when they are developed; edited copies and other people's photos don't.
- **Advancements** (`gameplay/RCTriggers`, `tools/gen_advancements.py`): triggers `realcamera:photo_taken`
  (entity, stars, phenomenon, biome, dimension, flag, shutter/distance/aperture/magnification/sky limits) and
  `realcamera:event` (prints, frames_hung, film_developed, edits, commissions, commission_tier, shutter_count,
  journal_creatures/biomes/phenomena). 37 advancements in their own tab, ru/en.
- **Newspaper editor** (`gameplay/NewspaperEditor`): villager profession at the **editorial desk** (POI, job site tag,
  outfit texture). Sells no equipment; buys paper and posts photo orders per level (cow… → panda in a bamboo jungle,
  sunset… → warden, Ender Dragon, lightning, star trails), paid in emeralds. The order is a `realcamera:commission`
  component on the trade's cost item; `mixin/ItemCostMixin` makes `ItemCost#test` accept any print whose analysis
  fulfils it (both for the trade and for the screen's auto-fill). Tooltip lists the order.
- Kit lenses (creative tab, autotests) are now chosen by suitability (everyday AF zoom for the sensor size), not
  registry order — with the stage 6 catalog the α7 IV had been getting a fisheye.
- Unit tests run inside the NeoForge test environment (`neoForge.unitTest`), so records with codecs can be tested.

## Verified

`./gradlew test` — 71 tests (`QualityScoreTest`, `CommissionTest`: orders and journal pages). `./gradlew
runClientAutotest -Pautotest=stage7`: 16 PASS — analysis stored with the photo, a sharp cow seen (3★), the same cow out
of focus (not sharp, 2★), encyclopedia page, root advancement, sunset recognised + advancement + page, the editor
offers orders, a cow print fulfils the cow order and not a pig order, a real trade through the merchant screen pays
emeralds and grants "Published", prints count. Screenshots of the encyclopedia, the editor's trades and the tab checked.

## Notes

- Entity visibility and size use bounding boxes, like the depth map.
- The order's cost item in the trade screen is a blank print; hover it for the order.
