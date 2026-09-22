# Aetheria

**High-performance level-of-detail rendering and local chunk caching for Minecraft 26.2 (Fabric).**

Aetheria draws the world far beyond the render distance your server is willing to send, and it
remembers the terrain you have already seen. It combines the two ideas behind Distant Horizons and
Bobby into one mod built from the start around a single constraint: **nothing expensive is allowed
to happen on the render thread.**

[![Build](https://github.com/finanzinstitut/aetheria/actions/workflows/build.yml/badge.svg)](https://github.com/finanzinstitut/aetheria/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## Features

### Extended rendering distance

Terrain is drawn out to a configurable ring — up to 4096 chunks — using compact LOD geometry rather
than real chunks. Detail halves with every doubling of distance, so the number of quads on screen
stays roughly constant instead of growing with the square of the render distance.

### Server-side chunk caching

Every chunk you visit is written to a local cache and read back the next time you are nearby. On a
multiplayer server this means the landscape stays on screen long after the server has stopped
sending it, and it is still there when you log back in tomorrow.

### Everything off the main thread

Block scanning, greedy meshing, down-sampling, compression and disk I/O all run on background
worker pools. The render thread walks a list of prebuilt meshes, culls them against the frustum and
submits them. Two separate pools are used, because the work has opposite characteristics: the mesh
pool is CPU bound and sized from your core count, while the I/O pool is small and latency bound, so
a slow disk can never occupy a core that meshing needs.

Work is ordered by distance from the camera, so the terrain about to come into view is always built
before the terrain behind you. Turning around does not mean waiting out a queue of work for chunks
you can no longer see.

### Aggressive greedy meshing

The mesher grows maximal rectangles of visually equivalent columns and emits one quad for each. A
flat desert or an ocean surface collapses from 256 quads to a handful. Cliff faces are closed with
skirts that always reach the lowest neighbouring surface, so no gap ever opens along an edge.

### Dynamic LOD scaling

Detail is biased coarser while the camera is high above the terrain or moving quickly, and relaxes
smoothly back as you settle. Flying with an elytra is exactly the case where the meshing pool would
otherwise fall behind the camera, and this is what keeps frame times flat while it happens.

### Low, bounded memory use

Distant terrain is never stored as block states. Each vertical span of visually uniform blocks is a
single packed `long`: height, depth, colour, block light, sky light and flags in 64 bits. Residency
is capped by a memory budget in megabytes rather than by a chunk count, because a fine chunk costs
many times what a coarse one does. Chunks past the budget are dropped from memory and reloaded from
disk in the background if you return.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer) **0.19.5 or newer** for
   **Minecraft 26.2**.
2. Install a Java 25 runtime. Minecraft 26.2 requires it; the official launcher provides one.
3. Download [Fabric API](https://modrinth.com/mod/fabric-api) for 26.2 and drop it into `mods/`.
4. Download the latest `aetheria-*.jar` from the
   [Releases page](https://github.com/finanzinstitut/aetheria/releases) and drop it into `mods/`.
5. Launch the game. A default `config/aetheria.properties` is written on first start.

Aetheria is **client-side only**. It needs no server component, and installing it on a server does
nothing at all.

Press **F6** in game to toggle the performance overlay, which shows resident memory, queue depths,
cache hit rate and the quads currently being drawn. The key can be rebound under
Options → Controls → Aetheria.

---

## Configuration

Settings live in `config/aetheria.properties`. Out-of-range values are clamped rather than
rejected, and unknown keys are ignored, so a file from another version will never stop the game
from starting.

### Rendering

| Key | Default | Meaning |
| --- | --- | --- |
| `render.lodRenderDistance` | `256` | How far distant terrain is drawn, in chunks (16–4096). |
| `render.fullDetailRadius` | `32` | Distance drawn at the finest level; detail halves per doubling beyond it. |
| `render.finestDetailShift` | `1` | Finest level used. `0` = per block, `1` = per 2×2, up to `4` = per chunk. |
| `render.dynamicDetailEnabled` | `true` | Reduce detail while flying high or moving fast. |
| `render.dynamicDetailLimit` | `2.0` | Maximum number of levels the dynamic terms may add (0–4). |
| `render.colorTolerance` | `12` | Per-channel colour difference that still allows a merge. Higher merges more. |
| `render.renderDistantWater` | `true` | Draw distant oceans and lakes. |

### Performance

| Key | Default | Meaning |
| --- | --- | --- |
| `performance.meshThreads` | `0` | Meshing threads. `0` sizes from your core count, leaving headroom for the game. |
| `performance.ioThreads` | `0` | Cache I/O threads. `0` picks a small default. |
| `performance.memoryBudgetMegabytes` | `256` | Soft ceiling on resident LOD data (32–8192). |
| `performance.spansPerColumn` | `4` | Vertical spans per column. More renders overhangs and caves better. |
| `performance.maxRegionBuildsPerFrame` | `8` | Caps how many areas are queued for rebuilding each frame. |

### Chunk cache

| Key | Default | Meaning |
| --- | --- | --- |
| `cache.enabled` | `true` | Store visited terrain on disk. |
| `cache.maxAgeDays` | `30` | Re-scan cached terrain older than this. `0` never expires it. |
| `cache.compactOnExit` | `true` | Reclaim holes in the region files when leaving a world. |

The cache lives in `.minecraft/aetheria-cache/<world>/<dimension>/`, where `<world>` is
`mp_<server address>` on a server or `sp_<save folder>` in single player. Scoping by world matters:
chunk coordinates repeat across every world in existence, so a cache keyed by dimension alone would
happily serve one server's terrain inside another world.

Deleting the cache is always safe; it is derived data and rebuilds itself as you play.

### Tuning advice

- **Low-memory machine:** lower `memoryBudgetMegabytes` and raise `finestDetailShift` to `2`. The
  memory budget bounds the resident set, not the render distance, so you can keep a long distance.
- **Many cores to spare:** raise `meshThreads` and lower `fullDetailRadius`'s companion
  `finestDetailShift` to `0` for block-accurate near terrain.
- **Stutter while flying:** keep `dynamicDetailEnabled` on and lower `maxRegionBuildsPerFrame`.
- **Slow disk:** raise `ioThreads` to 2–4, or turn `cache.enabled` off entirely; terrain will still
  render, it just will not persist.

---

## Building from source

Requirements: **JDK 25** and Git. The Gradle wrapper handles everything else.

```bash
git clone https://github.com/finanzinstitut/aetheria.git
cd aetheria

./gradlew build          # compile, run the tests and produce the jar
./gradlew test           # run the test suite only
./gradlew runClient      # launch a development client with the mod loaded
```

The finished mod lands in `build/libs/aetheria-<version>.jar`. The `-sources.jar` beside it is the
sources artifact and is not the file you install.

Dependency versions live in `gradle.properties`; check <https://fabricmc.net/develop> before
raising any of them.

Minecraft 26.2 ships unobfuscated, so the build uses no mapping set at all and the class names in
this repository are the real ones.

### Continuous integration

[`.github/workflows/build.yml`](.github/workflows/build.yml) compiles and tests every push and
pull request on JDK 25, and uploads the mod jar, the sources jar and the test report as artifacts.
Pushing a tag beginning with `v` additionally publishes a GitHub release with the jar attached:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

---

## Architecture

```
src/main/java/com/aetheria/
├── Aetheria.java                     Common entry point: configuration and paths
├── core/                             Pure Java. No Minecraft dependency, fully unit tested
│   ├── LodDataPoint.java             One vertical span packed into a single long
│   ├── LodChunk.java                 A chunk's spans in one flat long[], plus down-sampling
│   ├── LodDetailLevel.java           The five detail levels, block-accurate to per-chunk
│   ├── LodDetailPolicy.java          Distance, altitude and speed to a detail level
│   ├── GreedyMesher.java             Quad merging and cliff skirts
│   ├── LodMesh.java                  An immutable batch of quads in parallel primitive arrays
│   ├── LodMeshBuilder.java           Reusable scratch space that builds them
│   └── LodShadeTable.java            Precomputed brightness per face and light level
├── cache/
│   ├── LodRegionFile.java            32x32 chunks per file, sector allocated, deflated, CRC checked
│   ├── LodChunkStore.java            A dimension's region files, with a bounded open-file LRU
│   └── LodChunkCache.java            Resident chunks, memory budget, write-behind
├── concurrent/
│   ├── AetheriaExecutors.java        The mesh and I/O pools
│   └── PrioritizedTask.java          Distance-ordered work
├── config/AetheriaConfig.java        Settings, validation and persistence
├── client/
│   ├── AetheriaClient.java           Fabric API event wiring
│   ├── LodEngine.java                Per-world state and the pipeline
│   ├── AetheriaDebugHud.java         The F6 overlay
│   ├── world/WorldLodScanner.java    The only class that reads block states
│   ├── world/WorldIdentity.java      Scopes the cache to one server or save
│   └── render/
│       ├── LodRenderer.java          Region management, frustum culling, submission
│       ├── LodRenderRegion.java      8x8 chunks batched into one mesh
│       └── LodGeometrySubmitter.java The only class that writes vertices
└── mixin/client/CameraMixin.java     The only mixin: extends the far clipping plane
```

Two boundaries are deliberate and worth preserving:

- **`core/` and `cache/` have no Minecraft imports.** That is what makes the mesher, the packing
  format and the region file format testable without a game running, and it is why the test suite
  covers them properly rather than superficially.
- **Exactly one class writes vertices and exactly one class reads block states.** When a Minecraft
  update breaks the mod, those two files plus the single mixin are where to look.

### The pipeline

1. The client reports a chunk load. `WorldLodScanner` walks it on a worker thread, turning each
   column into up to four packed spans.
2. The result enters `LodChunkCache`: visible immediately, written to disk shortly afterwards.
3. `LodRenderer` groups chunks into 8×8 regions, has workers greedily mesh each region at the level
   `LodDetailPolicy` selects, and keeps the finished mesh.
4. Each frame it culls regions against the view frustum and submits the survivors. No meshing and
   no disk access happen inside a frame, and the steady-state path allocates nothing per region:
   each region keeps a reusable submission callback, region lookup avoids a capturing lambda, and
   per-quad brightness is a table lookup rather than arithmetic. The only real per-frame work left
   is writing vertices.

---

## Status and compatibility

Aetheria targets Minecraft 26.2 with Fabric Loader 0.19.5 and Fabric API 0.161.0+26.2. The build
compiles against those exact artifacts and the test suite runs green in CI.

Two honest caveats:

- The `core/`, `cache/`, `config/` and `concurrent/` packages are covered by the unit test suite.
  The rendering path can only be verified by running the game, which CI does not do; treat visual
  behaviour as untested until you have launched it yourself.
- `LodGeometrySubmitter` draws through `RenderTypes.debugQuads()`, the one public untextured-quad
  render type the 26.2 pipeline exposes. It re-submits vertices each frame rather than keeping a
  static GPU buffer, because 26.2 exposes no public API for registering a custom render pipeline
  (`RenderType.create` is package-private). Greedy meshing, frustum culling, the reusable
  submission callbacks and the precomputed shade table keep that path lean, but uploading once
  would still be faster; doing so needs either an access widener or a mixin into the pipeline
  registry, and it stays confined to that one class.

Aetheria makes no assumptions about the vanilla terrain renderer and should coexist with Sodium and
similar optimisation mods. Shader packs that replace the terrain pipeline may not apply their
shaders to LOD geometry.

---

## Contributing

Issues and pull requests are welcome. Please keep to the two boundaries above: new logic belongs in
the Minecraft-free packages with tests wherever it can, and anything that must touch the game's
internals should be as small and as well commented as possible.

Run `./gradlew build` before opening a pull request; CI runs the same command.

---

## License

Released under the [MIT License](LICENSE).

Aetheria is an independent project. It is not affiliated with Mojang, Microsoft, or with the
Distant Horizons or Bobby projects, whose ideas it builds on.
