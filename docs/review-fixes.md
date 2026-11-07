# Outside review (2026-11-06): fixes checklist

Four read-only reviewers (exposure/sensor, optics, camera mechanics + catalog, film + gameplay). The owner asked
to fix everything that is confirmed. `[x]` = done, `[-]` = declined (reason given).

## A. Camera mechanics and catalog
- [x] A1 Flash with electronic shutter: allowed on fast-readout bodies (readout ≤ 8 ms), sync = max(readout·1.2, 1/250)
- [x] A2 Leaf shutter is an explicit `leaf` flag (M6/M11 are focal-plane: sync 1/50, 1/180)
- [x] A3 Battery drain fractional (stochastic rounding); per-body drain = battery capacity / CIPA
- [x] A4 Film bodies: allowed exposure modes + meter type (battery/selenium/none); meter off when battery flat
- [x] A5 Allowed shutter types per body (no e-shutter on DSLR/film; efcs flag; R8/R50 no full mechanical)
- [x] A6 Camera buffer depth + card write speed drive burst slowdown
- [x] A7 Flash in Auto/P/A caps the shutter at sync speed (no band); band only when forced in S/M
- [x] A8 Sealing 2 still drowns, just slower
- [x] A9 Low-light AF threshold rises with slow lenses; DSLR phase AF limit f/8 (entry f/5.6)
- [x] A10 Jammed shutter blocks all firing; small failure chance before rated life
- [x] A11 Catalog: Zenit-E slowest 1/30; EOS-1V 3.5 fps; R8 e-shutter 1/16000, R50 1/8000; 5D IV CF+SD;
      X2D internal 1 TB SSD; XCD flange 18.14; Q3 OIS; bird detection on A1 II/Z8/Z9/R1/R5 II; Canon APS-C crop 1.6;
      X100VI hybrid finder; T2 flash GN + IR AF; R5 II LP-E6P; K1000 one SR44

## B. Exposure and sensor
- [x] B1 Correct exposure renders mid-grey: dark game frames are relit by the game light curve (night photos).
      Global factor from the metered area; per-pixel relighting (sunlit background blown out when metering for
      shade) is not done: the light grid is too coarse and would leave halos.
- [x] B2 Noise: gain ISO/100, scaled by sensor area (FF cleaner than APS-C/MFT)
- [x] B3 Overexposure does not add noise
- [x] B4 White balance before highlight roll-off, multipliers ≥ 1
- [x] B5 Contrast as an S-curve with fixed endpoints
- [x] B6 DR: fixed raw headroom (~1.2 EV), read noise scales with 2^-DR
- [x] B7 Real night/twilight/rain EV (no brightness toggle: night now needs a tripod or a high ISO, as in reality)
- [x] B8 Reflected metering: bright/dark subjects fool the meter
- [x] B9 Scene colour temperature: torch ~1900 K, sun height, overcast, shade
- [x] B10 Program line depends on ISO
- [x] B11 Auto ISO floor = base ISO; below-base ISO loses headroom
- [x] B12 DR values: X-H2S below X-S20/X-T30 II; Z6III below Zf
- [x] B13 Classic Chrome neutral whites (selective hue instead of global tint)
- [x] B14 RAW without lens corrections

## C. Optics
- [x] C1 Max magnification per lens (catalog), magnification() capped by spec, not 1:1 at 4f
- [x] C2 Blur cap w/40 → ~w/8
- [x] C3 Macro: FOV narrows with magnification, DoF from lens-to-subject distance, bellows factor
- [x] C4 Shifted lens: depth along the level axis (focus plane parallel to sensor)
- [x] C5 Vignetting/distortion/CA scaled by sensor diagonal / image circle
- [x] C6 Bokeh: round wide open; cat's eye and swirl fade when stopped down
- [x] C7 Diffraction blur and corner softness wide open
- [x] C8 Residual vignetting for wide lenses when stopped down
- [x] C9 (=A11) XCD flange; Canon APS-C 1.6
- [x] C10 Fisheye: do not compress the centre
- [x] C11 Bokeh discs: subtract gathered highlight energy
- [x] C12 Variable-aperture zooms step in 1/3 stops
- [x] C13 Hand shake as a short random walk

## D. Film and gameplay
- [x] D1 Newspaper orders: no reselling the same photo, author only, no fogged/ink-starved prints
- [x] D2 Negative film latitude (C-41/B&W normalised within latitude; slide 1:1); score tolerance per stock
- [x] D3 Push/pull: rate film at a different ISO; push compensates
- [x] D4 Stars: landscapes need focus; subject scoring only for subjects ≥ ~2% of frame
- [x] D5 Telephoto advancement: subjects within the client's entity render distance
- [x] D6 Server clamps reported settings to the camera/lens limits and checks the flash; exposure error and blur stay
      client measurements (bounded) and the javadoc says so
- [x] D7 Biome at the focus point, not where the photographer stands
- [x] D8 Lightning during long exposures counts
- [x] D9 Medium-format grain finer
- [x] D10 Offline photographer: queue photoStored on development
- [x] D11 Reciprocity failure for film
- [x] D12 Light leaks fade over the last frames; 120 needs no rewind
- [x] D13 RA-4 paper needs full darkness
- [x] D14 Printing uses paper chemistry; slides on negative paper
- [x] D15 film_developed counted on finish; removing the roll mid-process fogs it
- [x] D16 CineStill tint_b

## Found by the regression run afterwards
- Real night EV made AF fail in the dark: digital bodies now have an AF-assist lamp (subjects up to 5 m, slower).
- Auto ISO could drop below a film's box speed / a sensor's base ISO (`Exposure.isoFor` floor).
- A blurred main subject is capped at 2 stars.
- A jammed shutter refuses every shutter type, but the camera can still be raised (it shows its error).
- Development runs in its own pool with half the cores, so the game keeps rendering during a burst.
- Known limits kept on purpose: relighting is global (no per-pixel shade/sun contrast); with some shader packs at
  night the GPU depth is unusable and the ray grid (with its halo around mobs) is used; at 1:1 macro the frame
  shows a few centimetres of a Minecraft-sized bee, i.e. a texel or two.

## E. Physics check against real photography (2026-11-06)
The pipeline was compared with published optics and sensor physics (thin lens / DoF equations, Airy diffraction,
photon-transfer noise model, Rayleigh sky polarisation, cos^4 vignetting, guide numbers and HSS, Schwarzschild
reciprocity, dark current). What already matched stays as it was; the rest:
- [x] E1 Subject motion and panning (`processing/MotionBlur`): the frame is the middle of the exposure; static world
      smears by shake + pan (sign fixed: turning right moves the world left), plus parallax when the camera itself
      moves (`Motion.camV*`); moving entities found by the ray grid (`CameraRays.motionField`, box depth range
      refines the mask) are separate layers smeared with coverage over a push-pull-filled background. A pan that
      follows a runner keeps it sharp. ShotInfo blur = the AF subject's smear (`Motion.subjectBlur`).
- [x] E2 Flash light is a separate layer when anything moves: sharp at the start (or end, rear curtain) of the
      exposure, ambient smears.
- [x] E3 Rolling shutter: tilts stretch/squeeze (`rollingSkewY`); moving subjects lean by their own speed.
- [x] E4 HSS: ~1.7 EV entry loss, then 1 EV per stop; TTL raises the power for it (up to full). Flash light also
      loses the filter/lens/extension loss.
- [x] E5 Polariser: darkening = degree of skylight polarisation (Rayleigh sin²/(1+cos²), max 75%, × clear sky) at
      each sky pixel's angle from the sun (`Sun.dir*`); uneven skies on wide angles, nothing towards the sun.
- [x] E6 Bokeh radius cap w/8 -> 0.3 w (600 mm f/4 at 5 m: disc > half the frame).
- [x] E7 Noise sources add in quadrature; row banding (more on low-DR sensors).
- [x] E8 Focus breathing: internal-focus lenses reach the spec-sheet magnification at MFD and frame wider than at
      infinity there; the deviation fades with dioptres (`LensSpec.framingAt`). A zoom's stated magnification is
      scaled with the focal length (it used to be applied as a long-end correction at every focal length, so wide
      ends framed up to twice too wide and a 200-600 focused at 5 m acted like 110-408 mm); breathing is capped at
      0.75x (the catalogue's single minimum focus distance is often the short end's). The HUD ratio, the bellows
      factor and the analysis use the current focal length, not the long end.
- [x] E9 Diffraction: true per-channel Gaussian (σ = 0.42 λ N, λ = 600/530/460 nm) instead of a 3x3 blend.
- [x] E10 Axial colour (LoCA): red/blue focus Δ behind green; Δ from lens speed and √focal (`optics.loca`,
      "APO" lenses 0.2×, teleconverter k²); purple fringes in front of focus, green behind.
- [x] E11 Dark current: doubles per 6.5 °C (`SensorHeat`: biome air temperature, warmer with live view and long
      exposures); hot pixels at fixed places per sensor; long-exposure NR setting (default on) subtracts a dark
      frame and keeps the camera busy for as long again.
- [x] E12 Colour film drifts on long exposures (`reciprocity_tint`: Velvia green, Provia none).
- [x] E13 Lens transmission (T-stop): 0.15 EV primes, 0.3 EV zooms, +0.1 EV teleconverter (`transmission_loss_ev`);
      TTL metering compensates, a meterless camera does not.
- [-] Bayer mosaic / moiré: the photo is rendered below sensor resolution (a render pixel covers ~10 sensor
      pixels), so the sensor's own aliasing cannot appear at this scale; simulating it would add false colour a real
      camera does not show.
