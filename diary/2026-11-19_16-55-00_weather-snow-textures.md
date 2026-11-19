# Weather Fog, the Snow Field Texture and the Far Heights

- Time: `2026-11-19 16:55:00 +0200`
- Author: `Claude; Anthropic Opus 5.5, supervising DeepSeek (implementer, test writer and reviewer) and GLM (reviewer) subagents through Subordinate; implementing agent for empireu (outside contributor)`
- Status: `done` for this piece, in the working tree on `feature/weather` after `5f1883fd6`, not committed. Stage 1 of the weather plan continues with the shader walk and the hooks.
- Scope:
  - new `content/climate/render/fog/FarHeights` and `SnowFieldTextures`
  - `ClientSnowField` (the window of far heights, the texture accessors), `CalculatedSkyClientCommand` (`fog verify`)
  - new GameTest class `FarHeightsGameTests`
  - piece 8 of stage 1 of the weather rendering plan

## Completed

The snow field and the far heights now reach the GPU. Nothing draws from them yet.

**`FarHeights`** is plain, so GameTests drive it against the server level through `SnowFieldReader.ChunkLookup`.
- A window of 64 by 64 chunks, aligned to chunks and placed around the chunk corner nearest the eye, so it reaches at least 504 blocks on every side.
- Per column, the `MOTION_BLOCKING` height plus 1 in a `short[]` laid out as a 1024 by 1024 texture. Unknown columns hold `Short.MIN_VALUE`, which the far rule will take as open.
- Per chunk, the largest `WORLD_SURFACE` height plus 1, for the upper height of the vanilla fog bound. Block changes raise it and never lower it.
- Tiles that enter on a move are reset and read 128 per tick. Chunk loads are read at once, and block changes reread one column. While tiles reset by a fill or a move wait, `highestSurface` returns the maximum build height, so the bound stays off.

**`SnowFieldTextures`** owns a `GL_R8` 3D texture of the field (2 MiB) and a `GL_R16I` 2D texture of the heights (2 MiB).
- Uploads go by runs of dirty bricks or tiles along x, through staging buffers of one brick row (8 KiB) and one tile row (32 KiB), with the unpack row length and skip.
- The field uploads only when the solver is drained and the field has a solved box. The shader is to show cells only inside the shown box, which is the box of the last upload cut down by every later move, so no texel older than its meaning is shown.

**`ClientSnowField`** follows the window on the same ticks as the field, passes block changes and chunk loads to it, and offers `prepareTextures`, the texture ids and `highestSurface` for piece 9. `/calculatedSky fog verify` reads both textures back and compares them with the CPU arrays.

## Decisions

- Tests first: 11 GameTests check the window's placement and addressing against values worked out by hand, which tiles a move resets, read budgets, every column and chunk surface against the server world after fills, walks, a hidden chunk, block changes and chunks sent again, the upper height while reads wait, and 0 bytes for the warm operations.
- No 2 MiB mirror and no "a quarter dirty means a full upload" rule as the plan had it: runs along x keep a full upload to 256 calls for the field and 64 for the heights.
- Reverting `DustColumns` moves to piece 9, where the fog stops using its window.
- My review found that both run uploads staged their data at the run's x but left the unpack skip at 0, so every partial upload would have written the wrong texels. Full uploads hid it, because their runs start at 0. Fixed by setting the skip per run.

## Validation

- `agent-tools/build.sh` passes.
- The GameTests have not run. They run in stage 1's final pass.
- Two read-only reviews (DeepSeek on correctness, GLM on GL state and cost, `scratch/subordinate/weather/1h-review-*.md`). Applied: `highestSurface` and `verifyTextures` check that the tracked level is the game's, the field upload skips its scan when no brick is dirty, and `verify` sets all of the pack state. Rejected, with reasons in `WEATHER_PLAN.md` under piece 8: stale surfaces of chunks that left the window (a chunk that leaves shares its tile with the chunk that enters, which is reset), guarding against a bound pixel buffer (nothing in the game binds one), and the full scan of the window in `follow`.

## Documentation

None changed. The weather docs are written when the stage ends.

## Remaining

- The shader walk and the hooks (piece 9), which call `prepareTextures` and use the shown box.
- Not tried in game yet. `/calculatedSky fog verify` is the check for the uploads until piece 9 draws from them.
