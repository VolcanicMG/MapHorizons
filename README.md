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

By default MapHorizons paints everything your Distant Horizons has. Team scoping is an opt-in restriction: the
server tracks which chunks each player has actually been sent ("explored"), per dimension, and can limit painting
to those.

| Mode | Server config | What you see painted |
|---|---|---|
| Full atlas (default) | `teamScoped=false`, `showUnexploredTerrain=true` | All Distant Horizons data |
| Open | `teamScoped=false`, `showUnexploredTerrain=false` | What any player explored |
| Team | FTB Teams installed, `teamScoped=true` | What anyone on your team or an allied team explored (`shareWithAllies=false` limits it to your team) |

With team scoping and `showUnexploredTerrain=true` you see your team's exploration plus everything nobody has
explored; only other teams' explored areas stay hidden.

Be aware that this reveals where other teams have been: the unpainted gaps trace their trails and bases.

On a server without MapHorizons the client paints all DH data unless `paintWithoutServer=false`.

**Map painting is not a security boundary.** Distant Horizons still sends every client all LODs and renders them in
the 3D view. Team scoping only decides what appears on Xaero's map.

**Exploration from before the mod was installed is not credited.** Teammates' older travels appear once someone
revisits those chunks.

## Terrain sharing (multiplayer)

With Distant Horizons on the server too, MapHorizons fills in terrain your own DH has never received: the client
asks the server for the 64x64-block DH sections it is allowed to see (team mode: your team's and allies'
exploration; otherwise anything any player explored), nearest first, and stores them in your DH data. They then
appear on Xaero's maps and in DH's 3D view, and stay after you log out. Bandwidth is limited per player and in
total. Both sides need the same Distant Horizons network protocol; singleplayer never uses it. Sharing works per
64x64-block section, so in team mode it can reveal up to ~48 blocks beyond the edge of what your team explored.

## Nether

Ceiling dimensions (the Nether) paint the floor under the bedrock roof, matching what Xaero shows there: the roof
run is skipped and the highest block below the first air gap is used. Lava lakes darken with depth like water. The
End and other dimensions without a ceiling paint the topmost block.

## Config

Server (`<world>/serverconfig/maphorizons-server.toml`):

| Key | Default | Meaning |
|---|---|---|
| `teamScoped` | `false` | Opt-in: restrict painting to your FTB Teams team's exploration. |
| `shareWithAllies` | `true` | With `teamScoped`: also include FTB Teams allies. |
| `showUnexploredTerrain` | `true` | Paint terrain nobody explored (full atlas without team scoping). |
| `syncIntervalSeconds` | `5` | Seconds between exploration syncs. |
| `shareTerrain` | `true` | Send players DH terrain they may see but have not loaded (needs DH on the server). |
| `shareKBps` | `512` | Sharing bandwidth per player, KB/s. |
| `shareGlobalKBps` | `4096` | Sharing bandwidth for all players, KB/s. |

Client (`config/maphorizons-client.toml`):

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `colorMode` | `XAERO` | `XAERO`: Xaero's top-face texture colours, biome tints and see-through water, to match explored terrain. `DH`: Distant Horizons' own colours. Switching repaints. |
| `cacheRegions` | `48` | 512x512-block colour regions kept in memory (~1 MB each). |
| `shadeStrength` | `1.0` | Slope shading against the north/west neighbours (about Xaero's); `0` is flat. |
| `paintWithoutServer` | `true` | Paint all DH data on servers without MapHorizons. |
| `fetchMaxDistance` | `16384` | Only fetch shared terrain within this many blocks. |
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
