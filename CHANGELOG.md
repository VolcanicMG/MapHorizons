# Changelog

## 1.0.0 — 2026-10-23

First release of MapHorizons.

- Distant Horizons terrain painted into undiscovered chunks of Xaero's World Map (and the minimap in world-map
  mode), with water and lava depth darkening and height shading. Real Xaero terrain always wins.
- Server-tracked exploration per player (chunks actually sent to them), synced every `syncIntervalSeconds`.
- Modes: team (FTB Teams, allies included by default via `shareWithAllies`), open, and full atlas.
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
- Slope shading continues across 512-block region borders (no more straight lines at region edges).
- Xaero colours no longer switch off for the whole session after one failing block or a lookup after disconnect;
  the colour cache is cleared on logout and resource reload; a colour-mode switch mid-compute can't store old
  colours; a new server never inherits the previous connection's explored state.
- In-game settings: Mods > MapHorizons > Config, plus a "MapHorizons…" button on Xaero's World Map settings screen.
- Attaching to Distant Horizons is polled each tick from the current client level instead of relying on DH's
  level-load events, so singleplayer and multiplayer attach reliably; attach, detach and attach failures are
  always logged.
