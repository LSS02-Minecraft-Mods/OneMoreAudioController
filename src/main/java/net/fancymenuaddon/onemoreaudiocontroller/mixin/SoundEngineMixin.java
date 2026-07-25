package net.fancymenuaddon.onemoreaudiocontroller.mixin;

import net.fancymenuaddon.onemoreaudiocontroller.AudioControllerManager;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirects the volume of any sound covered by a JSON-defined controller (see
 * {@link AudioControllerManager}) to that controller's slider, instead of its vanilla
 * {@link net.minecraft.sounds.SoundSource} category.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    @Inject(
            method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
            at = @At("RETURN"),
            cancellable = true
    )
    private void onemoreaudiocontroller$customVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        AudioControllerManager.ControllerDefinition definition = AudioControllerManager.findBySound(sound.getLocation());
        if (definition != null) {
            cir.setReturnValue(Mth.clamp(sound.getVolume() * (float) definition.volume, 0.0F, 1.0F));
        }
    }
}
