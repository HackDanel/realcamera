# Stage 5 — film and the darkroom (2026-11-05)

## What was added

- **Film bodies**: `camera_body` got an optional `film` block (`format` 135/120, `mechanical`). Nikon FM2 and
  Leica M6 (mechanical: fire without a battery, the battery only runs the meter; manual focus because their AF
  speed is 0), Canon EOS-1V (AF, motor drive). Optical viewfinders no longer drain the battery while looking.
- **Film stocks**: new datapack registry `film_stock` (`spec/FilmStockSpec`: process c41/e6/bw, box ISO, frames,
  latitude, look, grain, halation). Nine stocks: Portra 400, Ektar 100, Gold 200, Tri-X 400, HP5 Plus 400,
  Delta 3200, Velvia 50, Provia 100F, CineStill 800T.
- **Film roll** item (`component/FilmRoll`: id, format, state fresh/loaded/exposed/developed/ruined, frames shot,
  push, fogged). It goes into the card slot of a film body. The ISO is locked to the box speed; film is
  daylight-balanced (tungsten for "T" stocks) and cannot auto white-balance. Exposure renders with the stock's
  look and a dynamic range from its latitude, then grain (more when underexposed) and halation
  (`processing/FilmLook`). Frames are stored on the server as **latent** (`PhotoIndex.Entry.latent`): nobody can
  download or list them, the camera has no playback and nothing is exported to disk.
- **Rewind** (camera menu, G). Taking an unrewound roll out in light > 3 fogs it (`CameraPartsMenu.openBack`).
- **Developing tank**: roll + matching chemistry (C-41 / E-6 / B&W kits, 8 rolls each), push/pull −1…+2,
  about a minute (`realcamera.fastLab` makes it 10× faster for the autotest). The reel must be loaded in the dark
  (no safelight nearby, `gameplay/Darkroom`) or with a changing bag, otherwise the roll is ruined. On the server
  `photo/FilmLab.developRoll` re-renders each frame (push/pull, light leaks: last frames burnt and streaks on the
  rest for an opened back; neutral on B&W film) and makes them visible.
- **Contact sheet**: right-click a developed roll.
- **Enlarger**: developed roll + darkroom paper (RC B&W, fibre B&W, RA-4 colour; the inkjet printer refuses them),
  frame choice, ±2 EV in half stops, contrast grade 0–5. The paper fogs unless only daylight-free safelight light
  reaches it. The print is a new stored photo derived from the frame (`FilmLab.enlarge` / `print`).
- **Safelight** block (light 7, does not count for paper, does for film).
- **Film scanner** (computer upgrade slot): developed rolls in the card slots can be browsed, rated, copied to a
  card (scan) and edited (saved onto the card in the other slot); frames can't be deleted or written.
- Recipes for all of it, block loot tables.

## Verified

`./gradlew test` (50 tests; film: grain, halation, push/pull, light leak, darkroom print grade/fog, latitude) and
`./gradlew runClientAutotest -Pautotest=stage5`: FM2 + Portra 400 (ISO locked, three frames counted, frames hidden
until developed), rewind, development with push +1 in a changing bag, contact sheet, safelit darkroom is dark for
paper but not for film, RC print, scanner copy to SD, frames on film can't be deleted, Leica M6 + Tri-X opened in
daylight → fogged, C-41 refused for B&W film, developed fogged roll shows burnt frames.

## Notes

- The client does not cache its own film frames at upload (the server changes them when developing).
- Light is not coloured in Minecraft: the darkroom check scans for light sources around the spot.
