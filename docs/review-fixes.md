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
