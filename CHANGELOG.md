# Changelog

## 1.1.0 — 2026-10-23

- Terrain sharing: in multiplayer the client fetches Distant Horizons sections it may see but has not loaded itself
  (beyond its own DH range) from the server's DH database, nearest first, and stores them through DH's own update
  path, so they show on Xaero's maps and in DH's 3D view and persist in the client's DH store. Team mode limits it to
  the team's (and allies') exploration; otherwise everyone's exploration is fetchable by everyone.
- Server config: `shareTerrain` (true), `shareKBps` (512 per player), `shareGlobalKBps` (4096). Client config:
  `fetchMaxDistance` (16384 blocks). Needs Distant Horizons with the same network protocol on both sides; never used
  in singleplayer.
- The explored sync always carries "explored by anyone" and the server's sharing protocol.
- Painting reads DH sections straight from the DH database, so far sections no longer trigger DH sync requests.
- Review fixes: the "explored by anyone" set is only sent when the client paints unexplored land (no leak of other
  teams' trails in team mode); oversized sections are reported missing instead of disconnecting the client; each
  section is fetched at most once per session; the fetch queue is distance-filtered, capped at 4096 and sorted only
  when it changes; region repaints while sections stream in are debounced to one per 1.5 s; rate limits follow
  config reloads.
- Sharing works per 64x64 section, so team mode can reveal up to ~48 blocks past the explored edge.

## 1.0.0 — 2026-10-23

First release of MapHorizons.

- Distant Horizons terrain painted into undiscovered chunks of Xaero's World Map (and the minimap in world-map
  mode), with water and lava depth darkening and height shading. Real Xaero terrain always wins.
- Server-tracked exploration per player (chunks actually sent to them), synced every `syncIntervalSeconds`.
- Modes: full atlas (default: paint everything your Distant Horizons has), open, and team (opt-in via `teamScoped`,
  FTB Teams, allies included via `shareWithAllies`).
- Nether support: paints the floor under the bedrock roof on Xaero's cave layers.
- Optional network channel; dedicated servers never load Xaero, DH or client classes.
- Empty regions are remembered, so Xaero does not keep re-requesting them; DH streaming only recomputes regions
  already on screen.
- A dimension change mid-read no longer turns painting off; only a DH API mismatch does.
- DH heights are read relative to the dimension's minimum Y (fixes the Nether roof check in dimensions with minY != 0).
- `cacheRegions` is capped at 256.
- Colours now come from Distant Horizons (texture average + biome tint, like the DH view) instead of flat vanilla
  map colours: the client level is used for colour lookups, and in singleplayer the matching DH level supplies
  the data. A failing colour lookup is logged once.
- Stronger per-pixel slope shading (default `shadeStrength` 1.0), closer to Xaero's look.
- Regions with missing DH sections are retried every 30 s; new `debugLogging` option to diagnose holes.
- New `colorMode` (default `XAERO`): Xaero's top-face texture colours with biome tints and see-through water, so
  painted terrain matches explored terrain; `DH` keeps Distant Horizons' colours. Falls back to DH colours if
  Xaero's colour code fails.
- Water now darkens the floor by depth like Xaero (one light level per block), so rivers and shallow seas read as
  water instead of land under a tinted sheet.
- Terrain from Distant Horizons' rough first pass (columns below the FEATURES generation step, stored as blocky
  2x2..16x16 squares) is shaded from a smoothed height field, so it no longer shows a grid of lines; fully generated
  terrain is shaded exactly as before.
- Slope shading continues across 512-block region borders (no more straight lines at region edges).
- Xaero colours no longer switch off for the whole session after one failing block or a lookup after disconnect;
  the colour cache is cleared on logout and resource reload; a colour-mode switch mid-compute can't store old
  colours; a new server never inherits the previous connection's explored state.
- In-game settings: Mods > MapHorizons > Config, plus a "MapHorizons…" button on Xaero's World Map settings screen.
- Attaching to Distant Horizons is polled each tick from the current client level instead of relying on DH's
  level-load events, so singleplayer and multiplayer attach reliably; attach, detach and attach failures are
  always logged.
