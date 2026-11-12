# Light pillar datasets

Tables for the light pillars of the calculated sky (`content/climate/render/sky`, see `docs/climate/calculated-sky.md`). They are loaded by `LightPillarEmitters`.

## Files

- `emitters.csv`: colors for lights whose particle texture gives the wrong color. One row per block id, or per block tag written with a leading `#`. Block ids are matched first, then tags in table order. `red`, `green`, and `blue` are a linear color in any scale, normalized to a brightest channel of 1 when loaded. `intensity` multiplies the light's strength, which is otherwise `(emission / 15)^2`, and must be above `0`. Rows for blocks of mods that are not loaded are ignored.

## Source

The colors are the average of a vanilla texture, computed the same way as `LightPillarEmitters.averageTextureColor` (opaque pixels, weighted by the fourth power of their brightest sRGB channel, averaged in linear light):

| Rows | Texture |
|---|---|
| `generator_t1`, `generator_t2`, `furnace` | `block/furnace_front_on` |
| `radiator` | `block/torch` |
| `campfire` | `block/campfire_fire`, first frame |
| `soul_campfire` | `block/soul_campfire_fire`, first frame |
| `blast_furnace` | `block/blast_furnace_front_on`, first frame |
| `smoker` | `block/smoker_front_on`, first frame |
| `#minecraft:candles` | `particle/flame` |

FrostedHeart's generators and radiator emit light from every block while active, and their textures would give white. Campfires would give the color of their logs, the furnaces the color of their stone, and candles the color of their wax.
