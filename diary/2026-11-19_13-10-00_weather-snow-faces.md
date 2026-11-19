# Weather Fog, Snow Faces

- Time: `2026-11-19 13:10:00 +0200`
- Author: `Claude; Anthropic Opus 5.5, supervising DeepSeek (implementer, test writer and reviewer) and GLM (reviewer) subagents through Subordinate; implementing agent for empireu (outside contributor)`
- Status: `done` for this piece, committed on `feature/weather` after `89d4ef16a`. Stage 1 of the weather plan continues with the solver.
- Scope:
  - new `content/climate/render/fog/SnowFaces`
  - new GameTest class `SnowFacesGameTests`
  - piece 5 of stage 1 of the weather rendering plan

## Completed

The shelter of the weather fog is being rebuilt as a snow-share field: each cell near the eye stores a weighted distance to open sky, and snow reaches a cell only through the faces between cells. `SnowFaces` is the part that knows the blocks. Nothing reads it yet.

**Faces.** Each block state gets a 16-bit mask per face, one bit per sub-square of a 4 by 4 grid, set where the face is closed.
- Faces are numbered by `Direction.get3DDataValue()`. Both faces of an axis use the same in-plane axes, so two neighbors' masks combine with a bitwise or.
- The outline shape at `BlockPos.ZERO` is sampled on a 16 by 16 by 16 grid of voxel centers. Each line of voxels along an axis covers the face whose end it touches, and both faces if it has a run that touches neither end. A sub-square is closed when 9 or more of its 16 pixels are covered.
- That rule equals "a box covers the faces it touches, and a box touching neither face of an axis projects onto both" for a single box, without depending on how a `VoxelShape` splits into boxes.
- A connected pane or fence closes the faces its connections separate, a lone post passes, a closed door closes one face, slabs close one face and half of each side, a 1-layer snow and carpets close only their bottom.

**Special cases, in order.** A fluid (water, lava, waterlogged) closes all faces. Leaves (`BlockTags.LEAVES`) have empty masks and a flag. A state whose reading throws is closed and logged once.

**Mask sets.** Distinct mask sets live in a table, with ids 0 (empty), 1 (full) and 2 (leaves) reserved. States are cached by `Block.BLOCK_STATE_REGISTRY` id, and shapes by object identity, since many states share one static shape. `clearStateCache` keeps the table, so stored ids stay valid after a tag update.

**Costs.** In eighths of a block, a face with open share `s = 1 - popcount(a | b) / 16` costs `round(24 * -ln s)`, from a 17-entry table, and `CLOSED_COST` when `s = 0`. A leaf face costs at least `round(24 * -ln porosity)` (porosity 0.3 gives 29), but a face both sides close together stays closed.

## Decisions

- Tests first: the expected masks and costs were worked out by hand from the vanilla shapes, the test writer built the tests against a placeholder class, and the implementer then replaced it.
- Rasterization by runs of voxels instead of boxes, as above. The two rules differ where boxes join: a fence connected north and east closes columns 2 and 3 of its north face but only column 3 of its south face. The first GameTest run caught this in the hand-worked fence expectations, and empireu kept the run rule.
- Leaves pass the smaller of their porosity and the neighbor's open share, so leaves next to stone do not leak.
- Reviewers' findings applied: the whole reading of a state inside the `try`, the shape cache, a javadoc. Rejected: range checks in the hot `faceMask`.

## Validation

- `agent-tools/build.sh` passes.
- `All 26 required tests passed` for `frostedheart_weather`, after the fence expectations were corrected. That includes the 13 new tests, one of which measures 0 bytes allocated by 100,000 warm lookups.
- Two read-only reviews (DeepSeek on correctness, GLM on cost and robustness). DeepSeek recomputed a sample of the expected masks from the vanilla shapes and agreed.

## Documentation

None changed. Nothing reads the class yet. The weather docs are written when the stage ends.

## Remaining

- The solver over these costs, then reading the world, the textures and the shader walk (pieces 6 to 9 of stage 1).
- Notes for the world reading: map each section's palette to mask sets once instead of per cell, check the time budget between cells because an unseen shape costs tens of microseconds, and construct `SnowFaces` after the registries are baked.
