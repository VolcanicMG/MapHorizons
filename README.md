# MapHorizons

Repository: https://github.com/VolcanicMG/MapHorizons · Issues: https://github.com/VolcanicMG/MapHorizons/issues

NeoForge 1.21.1 mod by VolcanicMG that paints [Distant Horizons](https://modrinth.com/mod/distanthorizons) terrain
onto Xaero's World Map, limited to what your team has explored. Chunks Xaero has never mapped get DH's colours;
anything Xaero has really mapped always wins. Nothing is written into Xaero's save data.

Xaero's Minimap in "use world map chunks" mode shows the same painted terrain, because it draws the World Map's
textures.

## Install

| Side | Requires | Optional |
|---|---|---|
| Server | NeoForge 21.1.262+ | FTB Teams (team scoping) |
| Client | NeoForge 21.1.262+, Xaero's World Map, Distant Horizons 3.3.x | Xaero's Minimap |

The network channel is optional both ways: a client with the mod can join a server without it, and the server only
sends to clients that have it. A dedicated server never loads Xaero, Distant Horizons or client classes.

## Modes

The server tracks which chunks each player has actually been sent ("explored"), per dimension.

| Mode | Server config | What you see painted |
|---|---|---|
| Team (default) | FTB Teams installed, `teamScoped=true` | What anyone on your team or an allied team explored (`shareWithAllies=false` limits it to your team) |
| Open | no FTB Teams, or `teamScoped=false` | What any player explored |
| Full atlas | `showUnexploredTerrain=true` and no team scoping | All Distant Horizons data |

With team scoping and `showUnexploredTerrain=true` you see your team's exploration plus everything nobody has
explored; only other teams' explored areas stay hidden.

Be aware that this reveals where other teams have been: the unpainted gaps trace their trails and bases.

On a server without MapHorizons the client paints all DH data unless `paintWithoutServer=false`.

**Map painting is not a security boundary.** Distant Horizons still sends every client all LODs and renders them in
the 3D view. Team scoping only decides what appears on Xaero's map.

**Exploration from before the mod was installed is not credited.** Teammates' older travels appear once someone
revisits those chunks.

## Nether

Ceiling dimensions (the Nether) paint the floor under the bedrock roof, matching what Xaero shows there: the roof
run is skipped and the highest block below the first air gap is used. Lava lakes darken with depth like water. The
End and other dimensions without a ceiling paint the topmost block.

## Config

Server (`<world>/serverconfig/maphorizons-server.toml`):

| Key | Default | Meaning |
|---|---|---|
| `teamScoped` | `true` | Scope to FTB Teams teams when FTB Teams is installed. |
| `shareWithAllies` | `true` | Also include FTB Teams allies. |
| `showUnexploredTerrain` | `false` | Also paint terrain nobody explored (full atlas without team scoping). |
| `syncIntervalSeconds` | `5` | Seconds between exploration syncs. |

Client (`config/maphorizons-client.toml`):

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `colorMode` | `XAERO` | `XAERO`: Xaero's top-face texture colours, biome tints and see-through water, to match explored terrain. `DH`: Distant Horizons' own colours. Switching repaints. |
| `cacheRegions` | `48` | 512x512-block colour regions kept in memory (~1 MB each). |
| `shadeStrength` | `1.0` | Slope shading against the north/west neighbours (about Xaero's); `0` is flat. |
| `paintWithoutServer` | `true` | Paint all DH data on servers without MapHorizons. |
| `debugLogging` | `false` | Log each painted region to `latest.log`. |

## Holes in the painting

Black 64x64 squares are areas Distant Horizons has no block data for yet. To check, set `debugLogging = true`
and look for lines like `region minecraft:overworld 3,-2: 61 sections indexed, 2 empty, 250000 columns painted`
(region = block coordinate / 512). Fewer than 64 indexed sections means DH has not generated those 64x64 areas;
"empty" means DH lists the section but has no data in it yet. Regions with holes are retried every 30 s and
repaint as soon as DH reports new data.

Distant Horizons only builds LODs for terrain it has generated or loaded. To fill the map from pre-generated
chunks (e.g. Chunky), enable DH's distant generation (`common.worldGenerator.enableDistantGeneration`) with a mode
that reads real chunks ("Pre-Existing Chunks only", or a full mode). Biome-only modes have no trees or real
surface blocks.

## Settings in game

Mods > MapHorizons > Config, or the **MapHorizons…** button in a free corner of Xaero's World Map settings
screen. Server settings are editable there in singleplayer. Client changes apply live.

## How it works

- A Xaero chunk highlighter (the API Open Parties and Claims uses) paints opaque DH colours into chunks Xaero has not
  discovered. Two small client mixins register it first in Xaero's highlighter list (so claim tints from other mods
  draw on top) and tell it which map tile is being drawn, so real Xaero terrain is never covered.
- Distant Horizons data is read from DH's own client database on one background thread, never the render thread.
  DH internals are isolated in `DhSource`; if a DH update breaks them, painting turns off instead of crashing.

## Build

```sh
mkdir -p libs   # copy the exact pack jars listed in build.gradle into libs/ (gitignored);
                # xaerolib comes from META-INF/jarjar/ inside the Xaero jars
JAVA_HOME=/path/to/jdk-21 ./gradlew build
```

`build` also runs `selfCheck`, an assert-based check of the bitsets and terrain maths. The jar lands in
`build/libs/maphorizons-1.0.0.jar`.

## License

MIT
