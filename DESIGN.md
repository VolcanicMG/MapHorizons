# MapHorizons design

Show Distant Horizons terrain on Xaero's World Map and Minimap, scoped to what your team has explored.
NeoForge 1.21.1 (21.1.262), Java 21. modid `maphorizons`, package `com.volcanicmg.maphorizons`, MIT, author VolcanicMG.
Sibling of MapColonies (`~/repos/colony-borders`): reuse its build, mixin gating, highlighter and sync patterns.

Team scoping only controls what is painted on Xaero's maps. It is not a security boundary: DH still sends
every client all LODs and shows them in the viewport.

## Client: paint DH terrain with a Xaero highlighter (approach A)

Verified against World Map 1.47.0:
- `MapProcessor.regionExists` returns true when `DimensionHighlighterHandler.shouldApplyRegionHighlights`
  is true, which needs highlighters built with `super(true)` (coveringOutsideDiscovered). Highlight-only
  regions get textures via `MapRegionHighlightsPreparer.prepare`, the same path OPAC/MapColonies claims use.
- `LeafRegionTexture.applyHighlights` calls `applyChunkHighlightColors(cx,cz,innerX,innerZ,src,dst,prepared,discovered,separate)`
  per chunk. `discovered = getHeight(innerX<<4, innerZ<<4) != 32767`. Blend: `orig*(1-a)+hl*a` with `a = colour & 0xFF`,
  so alpha 255 fully replaces. Packing `B<<24|G<<16|R<<8|A` (as MapColonies `ClientColonies.pack`).
- Every highlighter also runs on discovered chunks. Fix: mixin `@Inject` HEAD/RETURN of `LeafRegionTexture.applyHighlights`
  storing `this` in a ThreadLocal. `getChunkHighlitColor` returns null when
  `tex.getHeight((cx&3)<<4,(cz&3)<<4) != 32767` (real terrain wins) or
  `tex.getTileChunk().getInRegion().getCaveLayer() != Integer.MAX_VALUE` in a non-ceiling dimension (no surface paint
  in player cave mode). Ceiling dimensions paint on any layer (see Nether below).
- When the player explores a chunk for real, Xaero re-uploads it and real terrain replaces ours. Nothing is written to Xaero saves.
- The minimap in world-map mode draws WM `LeafRegionTexture.getGlColorTexture()` (`SupportXaeroWorldmap.renderChunks`),
  so highlights are already baked in. WM's `registerMinimapHighlighters` is empty, so nothing double-draws. No separate minimap highlighter.
- Registry hook: as MapColonies `WorldMapRegistryMixin`, but `getHighlighters().add(0, TERRAIN)` at HEAD of
  `HighlighterRegistry.end()`, so index 0 keeps MapColonies tints drawn on top.

Rejected: writing Xaero's internal map data (bakes DH data into saves, fragile, ~3x code); writing Xaero's
on-disk format (undocumented, versioned, overwritten); there is no Xaero API for external sources.

## Client: read DH data (DH 3.3.1 internals, isolated in one class `DhSource`)

Pin `distanthorizons` to `[3.3,3.4)`; catch `LinkageError` and disable cleanly.
- On a multiplayer client, `DhApi.Delayed.terrainRepo` reads `DhClientLevel.remoteDataSourceProvider`
  (`RemoteFullDataSourceProvider`): the client's SQLite of LODs received from the server, kept across sessions.
  With default DH settings this is full block detail (DetailLevel 0) everywhere.
- `SharedApi.getAbstractDhWorld().getLevel(ILevelWrapper).getFullDataProvider()` returns `FullDataSourceProviderV2`:
  `get(long)` (fresh source, caller must `close()`, empty source if missing), `addDataSourceUpdateListener(IDataSourceUpdateListenerFunc<FullDataSourceV2>)`,
  `repo.getAllPositions()`, `LEAF_SECTION_DETAIL_LEVEL = 6` (a leaf section is 64x64 blocks = one Xaero tile chunk).
- `DhSectionPos.encode/getX/getZ/getDetailLevel`. Section detail = data detail + 6.
- `FullDataSourceV2` implements public `IDhApiFullDataSource`: `getApiDataPointColumn(relX, relZ)` returns `DhApiTerrainDataPoint`s
  (`bottomYBlockPos`, `topYBlockPos`, `blockStateWrapper.isAir()/isLiquid()`, `biomeWrapper`). Surface = highest `topYBlockPos`
  among non-air points (don't rely on list order).
- Colour: public `IDhApiLevelWrapper.getBlockColorPreApi(state, biome, x, y, z, source)` returns `DhApiResult<java.awt.Color>`
  (DH's texture-average, biome-tinted colour). Needs a real `FullDataSourceV2`; null crashes. Fallback: vanilla `MapColor` of the block state.
- `DhApiChunkModifiedEvent` only covers locally loaded chunks; use the internal provider listener for network data.
  The listener fires on DH worker threads: only record the dirty section there.
- Public `DhApiLevelLoadEvent` / `DhApiLevelUnloadEvent` via `DhApi.events.bind(Class, instance)`; `levelWrapper.getWrappedMcObject()` is the `ClientLevel`.
- Reads block on SQLite: one dedicated daemon worker, never the render thread. `RemoteFullDataSourceProvider.get` sends a
  sync-on-load request per section the first time it is read; keep reads lazy (server limit ~50/s).

## Team scoping (server component)

- "Explored" = NeoForge `ChunkWatchEvent.Sent` (chunk actually sent to that player's client). Read player and dimension
  from the event, not the player's current position (they differ during teleports). One bit set, ~30 ns.
- Storage per player, per dimension: 32x32-chunk region key (long) -> 1024-bit set (`long[16]`). 4,096-block-radius
  exploration ~ 200-250 regions ~ 30 KB per player. One `SavedData` on the overworld (`maphorizons_explored`), saved when dirty.
  Per-player storage means team changes need no migration.
- A player's map draws from:
  - FTB Teams installed and `teamScoped=true`: current team members (`FTBTeamsAPI.api().getManager().getTeamForPlayer(sp)`,
    `Team.getMembers()`); solo player = own player team. `shareWithAllies` (default true) adds `getRankForPlayer(uuid).isAllyOrBetter()`.
  - Open mode (no FTB Teams, or `teamScoped=false`): all players.
- Team changes: FTB architectury `TeamEvent` (`PLAYER_CHANGED`, `PLAYER_JOINED_PARTY`, `PLAYER_LEFT_PARTY`, `ADD_ALLY`, `REMOVE_ALLY`,
  `DELETED`) mark online members of old and new team for full resync. Leaving takes your exploration with you; joining brings it.
  All FTB code in one `FtbTeamsCompat`, only loaded when `ModList.isLoaded("ftbteams")`.
- `showUnexploredTerrain` (default false): when true, the server also sends "explored by anyone" and the client paints its own set
  plus everything nobody explored. With `teamScoped=false` it becomes "full atlas": send `unrestricted=true` and no sets.
  Modes: team (default), open, full atlas.
- Sync (MapColonies shape): optional channel `registrar("1").optional()`, guard sends with `hasChannel`. Full sync on login
  (FTB `TeamEvent.PLAYER_LOGGED_IN` when installed, else `PlayerLoggedInEvent`). Every `syncIntervalSeconds` (5): per-player
  changed-region sets; for each online viewer OR the changed regions of everyone they draw from, send those regions as
  replacements, then clear. Exploration only adds bits, so replacement is always correct.
- `ExploredPayload(boolean full, boolean unrestricted, List<(dim, long[] regionKeys, long[] words)>)`, split under the 1 MiB cap by weight
  as in MapColonies `ServerSync`. Deflate only if measured necessary.
- Client: `allowed[dim] : map regionKey -> long[16]`; `full` clears. Apply the mask at read time (`chunkIsHighlit`,
  `getChunkHighlitColor`), so colours don't recompute on permission changes; bump the region version and
  `clearCachedHash(rx, rz)` on the render thread. `regionHasHighlights` needs DH data AND an allowed bit.
- Server without the mod: no channel -> client config `paintWithoutServer` (default true) paints all DH data.
- Exploration from before the mod was installed isn't credited; teammates' old travels appear once re-visited. Document it.

## Components

1. Build: copy colony-borders `build.gradle`; compileOnly exact pack jars in gitignored `libs/`: xaeroworldmap 1.47.0,
   xaerolib 1.7.3 (from the WM jarjar), DistantHorizons 3.3.1, ftb-teams-neoforge-2101.1.11 (+ architectury / ftb-library if javac needs them).
   Mod loads on both sides; dedicated server never touches client, Xaero or DH classes.
2. Mixins (client): `RegistryMixin` (HighlighterRegistry.end, add at 0), `LeafTextureMixin` (applyHighlights HEAD/RETURN ThreadLocal).
   Mixin plugin skips them unless Xaero WM and DH are loaded.
3. `DhSource`: level load: attach listener, build index Xaero region -> 64-bit tile-chunk mask from
   detail-6 positions, then `clearCachedHashes()`. Unload: detach and clear.
4. `RegionColours`: LRU of `int[512*512]` per region (`cacheRegions`, default 48 ~ 1 MB each). Worker computes from leaf sections:
   surface point, DH colour, darken liquids by depth to next solid point, shade by height vs north/west neighbours
   (`shadeStrength`), pack. Per-region version only increases on recompute/permission change, never on eviction.
5. `TerrainHighlighter extends xaero.map.highlight.ChunkHighlighter`, `super(true)`: DH index + allowed mask; cache miss ->
   null + enqueue; else copy 16 rows into `resultStore`. `getColors` and tooltips null.
6. Server: `ExploredData`, `ExploredSync`, `FtbTeamsCompat`, `ExploredPayload`.
7. Config. Server: `teamScoped=true`, `shareWithAllies=true`, `showUnexploredTerrain=false`, `syncIntervalSeconds=5`.
   Client: `enabled`, `cacheRegions`, `shadeStrength`, `paintWithoutServer=true`.
8. One runnable check (`./gradlew selfCheck`, part of `build`): bitset upsert/OR/mask, surface pick/shading/packing on
   synthetic columns, and Nether columns (bedrock roof + air + netherrack floor, lava lake, no-gap fallback).

## Nether and other ceiling dimensions (v1)

- Xaero WM 1.47.0 has no `hasCeiling()` special case. Its default view of the Nether comes from auto cave mode:
  `MapProcessor.updateCaveStart` uses `CaveStartCalculator.getCaving` (scans blocks above the player) and
  `getCaveLayer(caveStart) = caveStart >> 4`, so under the roof the Nether renders a cave layer, not `Integer.MAX_VALUE`.
  The cave layer follows the player's Y. So in ceiling dimensions the highlighter paints on any cave layer; in
  other dimensions it keeps skipping cave layers (player-toggled cave mode).
- Floor rule (`TerrainMath.pickSurface`, ceiling dims only): ignore points above the ceiling, skip the top solid run
  that reaches `ceilingY = logicalHeight - 1` (from the ClientLevel's `DimensionType`; DH point Y is relative to
  `minY` and `topYBlockPos` is exclusive), and take the highest
  non-air point below the first gap (air point or missing Y range). No gap: topmost non-air point. A run that does not
  reach the ceiling (hole in the roof) is the surface. Lava is liquid: same depth darkening as water.
- The End and every other dimension: no special case unless `hasCeiling()`.

## Colours (`colorMode`, default XAERO)

- XAERO: Xaero's full pixel routine (`MapBlock.getPixelColour` / `MapPixel.getPixelColours`) needs Xaero's
  `MapTile`/`MapTileChunk` graph, `MapProcessor`, `OverlayManager` and shape caches, so it cannot be fed DH columns
  without faking Xaero's tile structures. Instead `XaeroColours` uses Xaero's colour building blocks:
  `ClientOnlyServices.PLATFORM.getBlockUtils().getTextureColorUtils()` → `getBlockTextureColor(state, true, level,
  BuiltInRegistries.BLOCK, pos)` (cached top-face texture colour, as Xaero's map uses) and `getBlockTintIndex(state)`,
  times the vanilla `BlockColors` tint for that index evaluated in the DH column's biome. Xaero's caches are not
  thread-safe, so unknown (state, biome) pairs are resolved in one main-thread hop per region and memoised.
  Water is blended over the floor colour by depth (see-through water); lava is darkened by depth.
- DH: DH's `getBlockColorPreApi` (whole-model average, biome-tinted), with depth darkening for liquids.
- Any Xaero failure switches the session to DH colours and logs once. Switching the mode clears our cache and
  repaints.

## Implementation notes (deviations)

- Team events resync every online player instead of only the old and new team (rare events, simpler). A server
  config reload does the same.
- Height shading reads a 1-px apron (the south row / east column of the neighbouring leaf sections), so region
  edges shade like the interior.
- Element-level colour cache: one `getBlockColorPreApi` call per (block state, biome) pair per region compute.

Not in v1: coarse-detail fallback, disk cache of colours, minimap-without-world-map highlighter.

## Unverified until run in game

Night lighting of highlight-only pixels (alpha 0 may read as dark); minimap loading highlight-only regions with the world map
closed; `getBlockColorPreApi` off-thread safety and cost; per-region read time and sync-request burst; how closely
`ChunkWatchEvent.Sent` matches Xaero's write distance; minimap "use world map chunks" setting.
