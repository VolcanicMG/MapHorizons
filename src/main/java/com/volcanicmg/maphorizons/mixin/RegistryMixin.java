package com.volcanicmg.maphorizons.mixin;

import com.volcanicmg.maphorizons.client.xaero.TerrainHighlighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.highlight.HighlighterRegistry;

/** Adds the terrain highlighter at index 0 before Xaero freezes the registry, so claim tints draw on top. */
@Mixin(value = HighlighterRegistry.class, remap = false)
public abstract class RegistryMixin {
    @Inject(method = "end", at = @At("HEAD"))
    private void maphorizons$register(CallbackInfo ci) {
        ((HighlighterRegistry) (Object) this).getHighlighters().add(0, TerrainHighlighter.INSTANCE);
    }
}
