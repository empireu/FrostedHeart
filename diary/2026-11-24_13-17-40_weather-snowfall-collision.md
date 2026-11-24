# Falling Snowflakes Stop at Blocks and Leave Short-Lived Splats

- Time: `2026-11-24 13:17:40 +0200`
- Author: `Claude Opus 5.5, supervised by empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `2026-11-23_19-02-31_weather-snowfall-near-flakes.md`, not committed. Second piece of stage 5 of `WEATHER_PLAN.md` (workspace root, not in this repository).
- Scope:
  - New: `SnowShapes`, `SnowShapeVolume`, `SnowShapeTextures`, `SnowCollisionGameTests`
  - Changed: `SnowfallRenderer`, `SnowFlakes`, `ClientSnowField`, `SnowSectionMasks`, `SnowFaces`, `SkySetting`, `snow_flakes.glsl`, `snow_flake_update.fsh`, `snow_flakes_draw.glsl`

## Completed

**Why.** The flakes of the first piece passed into walls and through the ground. empireu wants them to stop where they hit, with a short-lived splat on the face, so a flake that reaches a wall or the ground looks like it lands there.

**What changed.**
- `SnowShapes` is a table of block shapes at a sixteenth of a block. A shape is 256 rows of 16 bits (the row of y and z, the bit of x), rasterized with the voxel-center rule of `SnowFaces`, whose two voxel helpers are now package-private for it. A state's shape is its outline shape when it has a collision shape and nothing otherwise, so grass, flowers, torches and signs are empty while fences, slabs and snow layers are what they look like. A state with a fluid adds a box up to its fluid height. Ids carry two flags: `LEAVES_FLAG` (flakes pass a leaf block at `fogLeafPorosity`, decided once per flake and block by a hash) and `FLUID_FLAG` (a flake that reaches water vanishes without a splat). Shapes are interned by content and cached per shape object and per state, like `SnowFaces`.
- `SnowShapeVolume` holds the ids of a 64-block cube around the eye in toroidal slots, the layout of a 3D texture. It follows the eye on the client tick, clears the cells that enter and queues them, fills after a jump of more than 16 blocks, and reads queued boxes one section at a time through `SnowSectionMasks`, which now takes any id function (the old constructor passes `SnowFaces::maskSetOf`). Unread cells, unloaded chunks and cells outside the world are empty. It marks 8-block bricks dirty for the upload.
- `ClientSnowField` keeps the volume beside the snow field: it follows the eye while `snowEnabled` is on and is forgotten otherwise, hears the same block changes, chunk loads and tag updates, and reads up to 16384 of its cells each frame before the field's budget.
- `SnowShapeTextures` uploads the ids as an R16UI 3D texture in runs of dirty bricks, and the shapes as an R16UI 2D texture of 16 by 16 tiles, 64 tiles across, growing by doubling when the table outgrows it.
- The update pass now writes two RGBA32F targets through MRT, the flakes' state and their splats, with the output locations read after linking as the froxel integrate does. A flake is tested every quarter block of its move (at most 16 tests a frame) at its drawn place, flutter included. On a hit it narrows the hit to a sixty-fourth of a block, finds the face it entered from the voxel it crossed into, writes the point, the face and an age of 0 into its splat, and stops.
- A stopped flake is the key design choice. It keeps moving, unseen and without collision, until it leaves the box seen from the camera, and then comes in at the opposite face as a new flake. The plan said a hit flake respawns at once. That was changed because a respawn anywhere in the box would pop flakes into view and pile them up toward the ground. A respawn at the top face would leave the gaps under roofs and trees in place as the camera walks, since a periodic box hands the flakes leaving one face to the opposite face. With stopped flakes, all flakes stay evenly spread through the box, so the count formula of the first piece still holds. Flakes show only between the box face they came in at and the block they hit, which gives lee shadows behind walls along the flakes' path. A flake enters only at a box face, where the box fade hides it. The update tells that a flake left the box from its offset at the last update, its step, and the camera's step since, so a teleport brings every flake in anew. A flake that starts inside a shape stops without a splat, for blocks placed on it and cells read late.
- The draw hides stopped flakes and draws the splats first in a second instanced draw of the same program (`splatPass`): a quad on the face, lifted 0.01 blocks against depth fighting, 1.3 times the flake's width, with the flake's clump shape turned by its hash, lit as snow lying on the face (the fog color J, which is a white top face at full sky light, times vanilla's shade for the face and the mean color of vanilla's snow texture, 0.977 0.997 0.997), fogged like a flake, hidden when its face turns away from the camera, and faded by the shelter of the air half a block in front of the face, so splats of hidden flakes indoors do not show. It fades over the second half of `snowSplatSeconds`.
- The state is filled again when `snowNearRadius` changes, since positions in a box of another side mean other places.
- Settings: `snowCollision` (true) and `snowSplatSeconds` (1.5 s, 0 to 10, 0 leaves no splats), placed after the other snow settings and before the fog settings.
- `/calculatedSky snow` adds whether the last update collided, the shapes in the table, the cells read, the fills, the box overflows, the boxes waiting, and the bricks uploaded since the last report.
- `SnowFlakes` gains the Java twin of the hit search (`hitIdAt`, `findHit`, `hitFace`), the box-leaving test (`leavesBox`), the sub-step count, the leaf pass hash and the splat fade.

## Validation

- `agent-tools/build.sh` compiles the main and GameTest sources.
- `agent-tools/glsl-check.sh` passes `snow_flake_update.fsh` (with `snow_flakes.glsl`) and both stages of `snow_flakes_draw.glsl` (with `weather_fog.glsl`, `weather_fog_excess.glsl` and `snow_flakes.glsl`).
- `SnowCollisionGameTests` checks the shapes of real states (slabs, a fence post, snow layers, water, a waterlogged slab, leaves, grass and torches empty, stairs facing two ways), the volume against a standalone chunk through a fill, a move, a chunk sent again, a block change with its one dirty brick, a jump and a forget, the hit search on the faces of a block from above, from the side, along a slanted path and over a long step, on a bottom slab, above it, from inside and in water, the leaf porosity, the box-leaving test, the sub-step count and the splat fade.
- `agent-tools/gametest.sh frostedheart_weather` gives `All 135 required tests passed`, which includes the 5 tests of `SnowCollisionGameTests` and the 4 of `SnowFlakesGameTests` from the first piece, run here for the first time.
- Not checked in game yet. Not measured yet: the GPU time of the update with collision and of the splat draw, and the bytes allocated per frame.

## Documentation

No living docs changed, because the weather docs are written in the wrap-up stage of the plan.

## Remaining

- The lee behind an obstacle shows only while the obstacle is inside the flake box. With the obstacle upwind outside the box, flakes come in at the box's face in its lee and fill it, so the same air gets snow or none depending on where the eye stands. The share field cannot help, because it does not depend on the wind. The plan's next piece is a lee map from the far heights along the wind, which the far snow needs as well.
- A splat is lit for full sky light, so it is a little too bright on a face in a darker spot.
- The flakes collide with blocks only, not with entities, the player included.
- Collision covers the 64-block cube, so with `snowNearRadius` above 31 the flakes near the box faces do not collide, where they are faded out.
- A splat on the outer face of a window pane is hidden from inside, since its face turns away from the camera.
- Blocks without a collision shape at the default position pass flakes even when they look solid, such as powder snow and a single snow layer, whose collision is empty. The flake then disappears into the surface by the depth test.
- Offset blocks with collision, such as bamboo and pointed dripstone, get the shape of their offset at BlockPos.ZERO.
