# Stage 2 — optics and the digital camera in depth (2026-11-05)

## What was added

- **Optical chain** (`camera/OpticalChain`): body → mount adapter → teleconverter → lens, with physical
  compatibility checks, effective focal length and f-number (teleconverter light loss), AF speed through
  adapters, "manual focus only" for adapters without electronics (EXIF lens "----"), and APS-C lenses on
  full frame: automatic crop (smaller file) or full sensor with the dark corners of the small image circle.
- **Catalog additions**: mounts Canon EF / Nikon F / Leica M; Nikon D750 (optical viewfinder), Leica M11
  (rangefinder); Sony E PZ 16-50, FE 200-600 G, Nikon AF-S 50/1.8G, Canon EF 50/1.8 STM, Summicron-M 50/2;
  adapters Sigma MC-11 (EF→E), F→E, M→E; Sony 1.4x/2x teleconverters; colour profiles of Sony, Nikon, Canon,
  Fujifilm, Leica, Hasselblad.
- **Development pipeline**: hand shake left over after stabilisation (1/focal rule) and panning as
  directional blur, rolling-shutter shear for the electronic shutter, lens distortion and lateral chromatic
  aberration (mostly removed by in-camera "lens corrections", as on real cameras), bokeh highlights shaped by
  the aperture (polygon for straight blades, cat's eye towards the edges), sun flare (veiling glare, ghosts,
  starburst when stopped down).
- **Long exposures** (≥ 1/4 s) and **bulb**: every rendered frame during the exposure is accumulated in linear
  light, so moving things leave trails; the viewfinder blacks out while the shutter is open.
- **Shutter types**: mechanical, electronic first curtain (quieter), electronic (silent, no wear, rolling
  shutter). Sound sets per body: mirrorless, DSLR mirror slap, quiet leaf/rangefinder.
- **Drive**: single, continuous Lo/Hi/Hi+ with a buffer (frames wait for development and upload), self-timer
  2 s / 10 s. Uploads are queued so bursts reach the server one by one.
- **Autofocus**: wide / zone / spot (arrow keys move the point) / tracking; subject detection by body class
  (people → eye, animals, birds, vehicles) with boxes in the viewfinder; continuous AF follows the subject.
- **Manual focus aids**: focus peaking (in-focus edges from a ray-cast depth grid) and a ×5 magnifier (H).
- **Viewfinders**: electronic (exposure preview, histogram), optical (no preview, AF point pattern, green data
  strip), rangefinder (wider view with bright frame lines and a focusing patch).
- **RAW / RAW+JPEG**: the unprocessed frame as PNG plus a JSON sidecar with the development settings (for the
  stage 4 editor); card usage follows real RAW sizes.
- `/photo view <id>` and `/photo quota <player> [n|reset]` (stage 1 leftovers).

## Verified

`./gradlew test` (30 unit tests) and `./gradlew runClientAutotest -Pautotest=stage2` (all PASS with Iris +
Complementary): adapters, teleconverter, crop, both new viewfinders, burst, subject detection, magnifier, sun
in frame, night bokeh, 2 s handheld pan, 8 s and bulb exposures, RAW+JPEG files.

## Notes

- Burst speed is limited by rendering each frame at sensor resolution: about 3 fps on the test iGPU with
  shaders, whatever the body allows.
- The autotest world has the daylight cycle frozen, so star trails are not visible there (the sky does not move).
