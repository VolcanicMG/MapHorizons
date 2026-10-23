package com.volcanicmg.maphorizons;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server and client config specs. */
public final class Config {
    public enum ColorMode { XAERO, DH }

    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.BooleanValue TEAM_SCOPED;
    public static final ModConfigSpec.BooleanValue SHARE_WITH_ALLIES;
    public static final ModConfigSpec.BooleanValue SHOW_UNEXPLORED_TERRAIN;
    public static final ModConfigSpec.IntValue SYNC_INTERVAL_SECONDS;
    public static final ModConfigSpec.BooleanValue SHARE_TERRAIN;
    public static final ModConfigSpec.IntValue SHARE_KBPS;
    public static final ModConfigSpec.IntValue SHARE_GLOBAL_KBPS;

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue CACHE_REGIONS;
    public static final ModConfigSpec.DoubleValue SHADE_STRENGTH;
    public static final ModConfigSpec.BooleanValue PAINT_WITHOUT_SERVER;
    public static final ModConfigSpec.IntValue FETCH_MAX_DISTANCE;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ModConfigSpec.EnumValue<ColorMode> COLOR_MODE;

    static {
        ModConfigSpec.Builder s = new ModConfigSpec.Builder();
        TEAM_SCOPED = s.comment("Opt-in restriction (needs FTB Teams): only paint what your team explored.",
                        "False (default) = no team restriction.")
                .define("teamScoped", false);
        SHARE_WITH_ALLIES = s.comment("With teamScoped: also paint what FTB Teams allies explored. False = your own team only.")
                .define("shareWithAllies", true);
        SHOW_UNEXPLORED_TERRAIN = s.comment("Paint terrain nobody has explored. Default true with teamScoped=false = paint everything",
                        "your Distant Horizons has (full atlas). False = only what players explored.",
                        "Warning: with team scoping this reveals where other teams have been; the unpainted gaps",
                        "trace their trails and bases.")
                .define("showUnexploredTerrain", true);
        SYNC_INTERVAL_SECONDS = s.comment("Seconds between exploration syncs.")
                .defineInRange("syncIntervalSeconds", 5, 1, 300);
        SHARE_TERRAIN = s.comment("Send players the server's Distant Horizons terrain for areas they may see but have not loaded",
                        "themselves (needs Distant Horizons on the server). Team mode: the team's (and allies') exploration.",
                        "Otherwise everyone's exploration is fetchable by everyone.",
                        "Sharing works per 64x64-block section, so it can reveal up to ~48 blocks past the explored edge.")
                .define("shareTerrain", true);
        SHARE_KBPS = s.comment("Terrain sharing bandwidth per player, KB/s.").defineInRange("shareKBps", 512, 16, 65536);
        SHARE_GLOBAL_KBPS = s.comment("Terrain sharing bandwidth for all players together, KB/s.")
                .defineInRange("shareGlobalKBps", 4096, 16, 1048576);
        SERVER_SPEC = s.build();

        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        ENABLED = c.define("enabled", true);
        COLOR_MODE = c.comment("XAERO: Xaero's top-face texture colours, biome tints and see-through water, to match explored",
                        "terrain. DH: Distant Horizons' own colours (whole-model average).")
                .defineEnum("colorMode", ColorMode.XAERO);
        CACHE_REGIONS = c.comment("512x512-block regions of colours kept in memory (~1 MB each).")
                .defineInRange("cacheRegions", 48, 4, 256);
        SHADE_STRENGTH = c.comment("Height shading against the north/west neighbours, 0 = flat, 1 = about Xaero's slopes.")
                .defineInRange("shadeStrength", 1.0, 0.0, 2.0);
        PAINT_WITHOUT_SERVER = c.comment("On servers without MapHorizons, paint all Distant Horizons data.")
                .define("paintWithoutServer", true);
        FETCH_MAX_DISTANCE = c.comment("Only fetch shared terrain within this many blocks of you.")
                .defineInRange("fetchMaxDistance", 16384, 512, 1_000_000);
        DEBUG_LOGGING = c.comment("Log each painted region (sections indexed / empty / columns) to latest.log.")
                .define("debugLogging", false);
        CLIENT_SPEC = c.build();
    }

    private Config() {
    }
}
