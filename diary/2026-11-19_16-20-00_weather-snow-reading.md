# Weather Fog, Reading the World into the Snow Field

- Time: `2026-11-19 16:20:00 +0200`
- Author: `Claude; Anthropic Opus 5.5, supervising DeepSeek (implementer, test writer and reviewer) and GLM (reviewer) subagents through Subordinate; implementing agent for empireu (outside contributor)`
- Status: `done` for this piece, committed on `feature/weather` after `0920b2d1c`. Stage 1 of the weather plan continues with the field's textures.
- Scope:
  - new `content/climate/render/fog/SnowSectionMasks`, `SnowFieldReader` and `ClientSnowField`
  - `SkySetting` (`fogLeafPorosity`, `fogFieldBudgetMicros`), `LightPillarWorldChanges` (two hook calls), `CalculatedSkyClientCommand` (one line in `fog`)
  - new GameTest class `SnowFieldReaderGameTests`, and `FogSettingsGameTests` for the two settings
  - piece 7 of stage 1 of the weather rendering plan

## Completed

The snow field now follows the client world. Nothing draws it yet.

**`SnowSectionMasks`** maps the palette of one chunk section to mask set ids, one `SnowFaces.maskSetOf` per palette entry instead of two hash lookups per cell. It follows a palette that grows in place or is replaced by a resize. A global palette is looked up per cell.

**`SnowFieldReader`** is plain, so GameTests drive it against the server level through a `ChunkLookup`.
- Boxes of cells wait in a ring of 64: the whole cube after a fill, the entering slabs after a move, a chunk's columns after a chunk load. A full ring is replaced by the whole cube, which reads cells twice but stays correct.
- `read` takes a step budget and can stop at any cell. It reads by chunk column, then from the top down, sets the column heights from the `MOTION_BLOCKING` heightmap before a column's cells, reads cells below the world as closed and above it as open, leaves unloaded chunks unread, and calls `SnowField.readsComplete` when the ring is empty.
- `onBlockChanged` sets the column's height and the cell's mask set at once.

**`ClientSnowField`** creates the faces, the field (about 12 MiB) and the reader on the first tick that wants them. It fills on joins, level changes and tag updates (after clearing the state cache), follows the camera's block every tick, calls `resolveAll` when `fogLeafPorosity` changes, and spends `fogFieldBudgetMicros` (default 500) per frame on reads and solver steps. An error turns it off with one log line until the fog is switched off and on.

## Decisions

- Tests first: 14 GameTests compare every cell of the cube (mask set, read state, column height) against the server world after fills, walks, lagging reads, step budgets of 1 and 7, block changes, a hidden chunk, jumps and ring overflows, and the distances against a fresh solve in the exact region. Five more cover every palette kind of a section, growth in place and a resize.
- The read order is top down, not nearest sections first as the plan said. Nothing shows until every read is done and the solver has drained, so top down, which lets each column fill from its seed in one pass, is cheaper with no visible difference.
- A chunk sent again keeps its palette's data object when the bit count is unchanged and refills it in place. My spec assumed otherwise, and both reviewers found it from the sources. `queueChunk` now drops the palette table, and a test sends a section again in place with `LevelChunkSection.read`.
- My review made the reader load a section's masks again whenever the section object changes, so a chunk unloaded and loaded again mid-read is followed.

## Validation

- `agent-tools/build.sh` passes.
- `All 60 required tests passed` for `frostedheart_weather` (pieces 5 to 7). The warm reads, moves, block changes and solving allocate 0 bytes.
- Two read-only reviews (DeepSeek on correctness, GLM on cost and robustness). Applied: the resend fix and the javadocs around it, and the wording of the field's debug line. Rejected, with reasons in `WEATHER_PLAN.md` under piece 7: a clip computed once per chunk column, which costs a little time after a move, the per-tick average label, catching `Error`s, and six boxes instead of three.

## Documentation

None changed. The weather docs are written when the stage ends.

## Remaining

- The field's textures and the far heights (piece 8), and the shader walk and hooks (piece 9).
- Not tried in game yet. Unmeasured: a fill's time at the default budget, how far the solved box lags in elytra flight, and the cost of a large decrease in the synchronous block change hook.
