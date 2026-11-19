# Weather Fog, Snow-Share Solver

- Time: `2026-11-19 15:20:00 +0200`
- Author: `Claude; Anthropic Opus 5.5, supervising DeepSeek (implementer, test writer and reviewer) and GLM (reviewer) subagents through Subordinate; implementing agent for empireu (outside contributor)`
- Status: `done` for this piece, not committed. Stage 1 of the weather plan continues with reading the world into the solver.
- Scope:
  - new `content/climate/render/fog/SnowField`
  - new GameTest class `SnowFieldGameTests`
  - piece 6 of stage 1 of the weather rendering plan

## Completed

`SnowField` is the solver of the snow-share field. It holds a cube of cells around the eye, 128 per axis in the game, each with a mask set id from `SnowFaces` and a distance byte, and keeps every byte equal to a capped shortest path from open sky while cells are read, blocks change and the cube follows the eye. It knows nothing about the level, so GameTests drive it directly. Nothing in the game calls it yet.

**Cells.** A byte is the distance in eighths of a block (0 to 192), `SHELTERED` (254) when no path within the cap reaches the cell, or `UNREAD` (255) before the cell is read. An unread cell's mask set is full, so no path crosses it. Cells are stored at their world position modulo the size, the layout of the GL 3D texture a later piece uploads.

**Costs and seeds.** A move costs 0 down, 8 sideways and 16 up, plus the face cost from `SnowFaces`. A read cell is a seed when it is the top of the cube or the cell above it is unread, and nothing that blocks motion lies above it by the column's height. Its seed cost is the cost of its top face against an empty cell.

**Protocol.**
- A bucket queue over the 193 distances, with lazy deletion and a fixed pool. A push that finds the pool full spills the cell into a bitset, which the solver takes back when the queue is empty.
- Every change (a read, a block change, a column height) collects the cells whose path may have become more expensive, with the old masks, including equal costs so free moves down reset too. A decrease resets them and everything that depended on them, and the refill pushes the cells around them and the seeds again. If the reset list overflows, `resolveAll` recomputes every byte from the stored masks.
- A move marks the entering cells unread at once, seeds the new top layer after a move down, and keeps every other byte. A jump of more than 16 blocks becomes a fill.
- `process` runs under a step budget. The solved box, the cells a renderer may show, becomes the whole cube once the reader has read everything and the solver is drained.

## Decisions

- Tests first: the test writer built 20 GameTests against a placeholder. Eleven layouts (porch, canopy, doorway, open door, cave, shafts, the cap, seeds under a roof above the cube, a pane in a gap beside a porch, a house across an unloaded chunk) have hand-worked distances. Every test also compares the whole cube, or its exact region after moves, against an independent Dijkstra in the test class.
- Four refinements of the plan's protocol, recorded in `WEATHER_PLAN.md` under piece 6: seeds below an unread cell, so a move up does not reset every open column; the overflow spill instead of a restart, which could livelock; a decrease that runs to completion, with `resolveAll` as its fallback and for porosity changes; and the solved box driven by `readsComplete`.
- My review found that the implementer stored cells relative to the origin, so every retained cell was read from the wrong slot after a move. The first GameTest run, before the fix, failed exactly the two tests that move the cube. I fixed the layout and made the spill take back at least one cell per call.

## Validation

- `agent-tools/build.sh` passes.
- `All 46 required tests passed` for `frostedheart_weather`, which includes the 13 tests of `SnowFacesGameTests` and the 20 new ones. The warm operations, edits, moves with reads and solving, allocate 0 bytes.
- Two read-only reviews (DeepSeek on correctness, GLM on cost and robustness). DeepSeek found the same slot bug and checked the reference Dijkstra and the hand-worked values. GLM found a stale comment and a missing javadoc note, both applied.

## Documentation

None changed. Nothing reads the class yet. The weather docs are written when the stage ends.

## Remaining

- Reading the world into the solver, the textures and the shader walk (pieces 7 to 9 of stage 1). The notes for the reader are in the plan under piece 7.
- Unmeasured: the cost of a fill, of a large decrease and of `resolveAll` at size 128 on the client thread. A decrease and `resolveAll` run without a budget and may take several milliseconds when they happen.
