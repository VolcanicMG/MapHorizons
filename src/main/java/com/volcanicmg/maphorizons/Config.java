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

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue CACHE_REGIONS;
    public static final ModConfigSpec.DoubleValue SHADE_STRENGTH;
    public static final ModConfigSpec.BooleanValue PAINT_WITHOUT_SERVER;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ModConfigSpec.EnumValue<ColorMode> COLOR_MODE;

    static {
        ModConfigSpec.Builder s = new ModConfigSpec.Builder();
        TEAM_SCOPED = s.comment("With FTB Teams: only paint what your team explored. False = everyone's exploration.")
                .define("teamScoped", true);
        SHARE_WITH_ALLIES = s.comment("Also paint what FTB Teams allies explored. False = your own team only.")
                .define("shareWithAllies", true);
        SHOW_UNEXPLORED_TERRAIN = s.comment("Also paint terrain nobody has explored. With teamScoped=false this is a full atlas.",
                        "Warning: with team scoping this reveals where other teams have been; the unpainted gaps",
                        "trace their trails and bases.")
                .define("showUnexploredTerrain", false);
        SYNC_INTERVAL_SECONDS = s.comment("Seconds between exploration syncs.")
                .defineInRange("syncIntervalSeconds", 5, 1, 300);
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
        DEBUG_LOGGING = c.comment("Log each painted region (sections indexed / empty / columns) to latest.log.")
                .define("debugLogging", false);
        CLIENT_SPEC = c.build();
    }

    private Config() {
    }
}
