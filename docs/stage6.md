# Stage 6 — full catalog and special optics (2026-11-06)

## What was added

- **Catalog generator** `tools/catalog.py` + `tools/gen_catalog.py`: 33 more bodies (39 total: Sony ZV-E10, α6400,
  α7R V, α1 II; Nikon D3500, Z50II, Z6III, Zf, Z8, Z9; Canon 250D, 5D IV, R50, R8, R6 II, R5 II, R1; Fujifilm X-T30 II,
  X-S20, X-T5, X-H2S, X100VI, GFX100 II; Panasonic S5II; Leica Q3; OM-1 II; Hasselblad X2D; film: Canon AE-1 Program,
  Pentax K1000, Olympus OM-1, Zenit-E, Hasselblad 500C/M (120, 6×6), Contax T2), 56 more lenses (63 total), 13 mounts,
  22 adapters (all obey the flange rule), 8 teleconverters, 17 batteries, CFexpress B and larger SD cards, Panasonic and
  OM colour profiles. New materials: stacked CMOS sensor, medium format sensor, titanium alloy. Recipes from tier/kind.
- **Models**: cylinders are now regular 16-sided prisms (8 rotated "spokes", the side texture wraps around in 16
  slices) — lenses, mounts, adapters look round. Lens finishes (`LENS_LOOKS`: Canon L red ring, white teles, Sony GM,
  Nikon S, chrome vintage with aperture ring, Fujifilm aperture ring, titanium…), fisheye front element with petal
  hood. Body styles: flat (α6400), retro (Zf/X-T5), retro SLR (AE-1/FM2), compact (X100VI/Q3/T2), pro with integrated
  grip (Z9/R1), big medium format (GFX/X2D), Hasselblad V box. Long lenses scale down in the inventory. Item frames
  show cameras from the front.
- **Fixed lenses** (`CameraBodySpec.fixed_lens`, mount `realcamera:fixed`): the body puts its lens in the slot on
  the first inventory tick, the parts menu won't give it out or take another; falls don't knock it.
- **Image circles**: any lens whose circle is smaller than the sensor vignettes (full-frame lens on medium format,
  6×6 Planar is fine on GFX).
- **Fisheye** (`optics.projection`: equisolid / stereographic / equidistant): render capped at 74° half-angle
  (`Optics.renderVfovDeg`), remapped per pixel in `ImagePipeline.fisheye`; circular fisheyes black outside 180°.
- **Tilt-shift** (`optics.tilt_shift`, Canon TS-E 24mm f/3.5L II): settings `tilt`, `shift`, dials TILT/SHIFT
  (only offered on such a lens). Shift: the view is pitched by atan(shift/f) and rendered 12 % wider, then
  `lensShift` re-projects onto a level sensor (parallel verticals). Tilt: per-row focus distance (Scheimpflug, hinge
  J = f / sin tilt).
- **Macro**: `max_magnification`; the viewfinder shows the reproduction ratio from 1:10 on (`Optics.magnification`).
- **Bokeh character** (`optics.bokeh`): `swirl` (Helios 44-2: background smeared along circles, discs squeezed into
  tangential ovals), `bubble` (Trioplan: bright thin rims), `busy` (onion rings). Bright small lights take the blur
  radius of their surroundings (no sharp dot inside bokeh discs).
- **Structure loot** (`gameplay/AddItemsLootModifier`, `tools/gen_loot.py`): cartographer houses (film, Zenit,
  Helios, Jupiter-9, B&W chemistry), village houses (film), shipwrecks (drowned bodies, UV filters, cleaning kits),
  buried treasure / stronghold library / mansion (Leica M6, Hasselblad 500C/M, Summicron, Planar, Trioplan, 120 film),
  ancient city / trial chambers (stacked & medium format sensors, titanium, Contax T2).

## Verified

`./gradlew test` — 62 tests: `SpecialOpticsTest` (magnification, projection round trip, capped render FOV, tilt focus,
fisheye bends lines, circular fisheye, shift straightens verticals, tilt band, bubble rim, swirl) and `CatalogTest`
(references, flange rule, models, en/ru names, recipes). `./gradlew runClientAutotest -Pautotest=stage6`: 15 PASS
(catalog size, fisheye/tilt-shift/shift/macro/X100VI/Helios/Trioplan photos, built-in lens fills and is locked, 120
roll with one 6×6 frame, loot over 600 chest rolls with broken bodies from shipwrecks); screenshots of the showcase
wall, the camera in hand and each viewfinder checked by eye, photos checked in `run-autotest/screenshots/realcamera/`.

## Notes / limits

- At 1:1 a frame is 36 mm wide — less than one texture pixel of a Minecraft block; macro shows texels.
- The viewfinder of a shifted lens shows the pitched (converging) view; only the photo is corrected.
- Depth from entity bounding boxes: a sharp halo around subjects shot wide open (see CLAUDE.md gotchas).
