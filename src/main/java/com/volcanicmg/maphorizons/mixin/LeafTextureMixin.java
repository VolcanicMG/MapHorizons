package com.volcanicmg.maphorizons.mixin;

import com.volcanicmg.maphorizons.client.xaero.LeafTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.highlight.DimensionHighlighterHandler;
import xaero.map.pool.buffer.PoolTextureDirectBufferUnit;
import xaero.map.region.texture.LeafRegionTexture;

/** Exposes the texture being highlighted so the terrain highlighter can skip chunks Xaero has real data for. */
@Mixin(value = LeafRegionTexture.class, remap = false)
public abstract class LeafTextureMixin {
    @Inject(method = "applyHighlights", at = @At("HEAD"))
    private void maphorizons$enter(DimensionHighlighterHandler handler, PoolTextureDirectBufferUnit buffer,
                                   boolean separateBuffer, CallbackInfoReturnable<PoolTextureDirectBufferUnit> cir) {
        LeafTextures.CURRENT.set((LeafRegionTexture) (Object) this);
    }

    @Inject(method = "applyHighlights", at = @At("RETURN"))
    private void maphorizons$exit(DimensionHighlighterHandler handler, PoolTextureDirectBufferUnit buffer,
                                  boolean separateBuffer, CallbackInfoReturnable<PoolTextureDirectBufferUnit> cir) {
        LeafTextures.CURRENT.remove();
    }
}
