package com.volcanicmg.maphorizons.client.xaero;

import xaero.map.region.texture.LeafRegionTexture;

/** The leaf texture Xaero is currently applying highlights to, per thread (set by LeafTextureMixin). */
public final class LeafTextures {
    public static final ThreadLocal<LeafRegionTexture> CURRENT = new ThreadLocal<>();

    private LeafTextures() {
    }
}
