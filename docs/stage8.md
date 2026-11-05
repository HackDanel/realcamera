# Stage 8 — compatibility and polish (2026-11-06)

## What was added

- **Recipe viewers** (`client/compat/`): plugins for **JEI**, **EMI** and **REI**, compiled against their APIs
  (`compileOnly`) and loaded only by the viewer itself (`@JeiPlugin`, `@EmiEntrypoint`, `@REIPluginClient`), so the mod
  runs without any of them. Three categories built from the same data (`RecipeViews`): Photo Workshop (every assembly
  recipe with counts, time, repair note), Film Development (exposed roll of each stock + its chemistry kit → developed
  roll; tank and changing bag as workstations), Darkroom Enlarger (developed roll + each darkroom paper → print).
  Catalog items are told apart by their model component (JEI subtype interpreter, EMI comparison, REI comparator).
  Synthetic recipe ids start with "/" (EMI's convention). Try one in dev: `./gradlew runClient -Pviewer=jei|emi|rei`.
- **Profiling and speed-up** (`PipelineBenchmarkTest`, 3840×2560): row loops of the pipeline (depth of field
  composite and pyramid levels, swirl, motion blur, rolling shutter, lens geometry, shift, fisheye, vignetting,
  sharpening, flare) run in parallel, sRGB encoding uses a 64k table. Portrait at f/1.8 3.5 s → 1.2 s, Helios swirl
  5.5 s → 1.4 s, fisheye 2.8 s → 0.8 s, landscape 2.3 s → 0.7 s. Noise stays sequential (seeded). The photo texture
  cache keeps its LRU VRAM budget (client config).
- **Recipe balance**: raw-material value per tier now grows monotonically for bodies and lenses (flagship lenses got
  ED elements and gold instead of motors; better film bodies cost gold/diamonds).
- **Translations**: `LangTest` — en/ru have identical keys and every complete key written in the code exists.
- Small parts (adapters, pancake lenses) are drawn bigger in slots and frames.

## Verified

`./gradlew test` — 76 tests. `runClientAutotest -Pautotest=stage8 -Pviewer=jei|emi|rei`: the viewer loads, 296 workshop,
9 development and 3 enlarger recipes listed, each category opened and checked on screenshots. All earlier autotests
re-run (they always run with Iris + Sodium + Complementary shaders enabled in `run-autotest`).
