# Stage 0 — capture spike results (2026-11-05)

Environment: NeoForge 21.1.265 (MC 1.21.1), Sodium 0.8.13, Iris 1.8.14-beta.1, Complementary Reimagined r5.9.3,
Intel i5-13500H iGPU (Mesa 26.2), window 1280×720.

All checks run unattended with `./gradlew runClientAutotest` (creates a fresh world, captures, quits).

| Check | Vanilla | Iris + Complementary |
|---|---|---|
| Clean frame at window resolution (no HUD, no hand) | OK, ~10–35 ms | OK, ~35–45 ms |
| Forced 3840×2160 frame | OK with 1 warm-up frame | OK with 1 warm-up frame, ~0.5 s hitch |
| Long exposure, per-frame accumulation (720p) | — | OK, 4.6 ms/frame overhead, ~24 fps |

## Decisions

1. **Capture point:** `RenderFrameEvent.Pre/Post`. Set `options.hideGui = true` in Pre (hides HUD and hand,
   also respected by Iris), read the main render target in Post (Post fires before the blit to screen, so
   restoring state there never shows a glitch). No mixin needed.
2. **High resolution:** temporarily set `Window` width/height, resize the main render target and call
   `GameRenderer#resize`, render **one warm-up frame**, grab the next one, restore. Without the warm-up frame the
   sky/fog band near the horizon renders black. Iris follows the resize automatically.
   The ~0.5 s hitch at 4K with shaders on an iGPU is hidden behind the viewfinder blackout during the shot.
3. **Long exposure:** CPU accumulation in linear light with double-buffered pixel-pack buffers
   (`glGetTexImage` into PBO, map the previous frame's PBO). Shader-independent. Done at window resolution;
   accumulating at 4K every frame would be too slow (each 4K frame with shaders costs ~0.5 s).
   Stage 2 decides how to combine it with sensor resolution (candidate: accumulate at window resolution for
   motion, plus one full-resolution frame for static detail).
4. **Sodium/Iris in dev:** Sodium 0.8 is a jar-in-jar wrapper and breaks when loaded from the dev classpath.
   The build copies the release jars into `<run dir>/mods` instead (`devMods` configuration).

## Spike code (to be replaced in stage 1)

`client/capture/CaptureSpike.java` (F8 window, F9 4K), `AccumulationSpike.java` (F10, 2 s exposure),
`SpikeAutotest.java` (`-Drealcamera.autotest=spike`). Output: `<run dir>/screenshots/realcamera_spike/`.
