package net.fancymenuaddon.onemoreaudiocontroller.client;

import net.fancymenuaddon.onemoreaudiocontroller.AudioControllerManager;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Builds a vanilla-styled slider {@link OptionInstance} for a JSON-defined custom controller. */
public final class CustomSoundOptions {

    private CustomSoundOptions() {
    }

    public static OptionInstance<Double> build(AudioControllerManager.ControllerDefinition definition) {
        return new OptionInstance<>(
                definition.translationKey,
                OptionInstance.noTooltip(),
                CustomSoundOptions::captionLabel,
                OptionInstance.UnitDouble.INSTANCE,
                definition.volume,
                value -> AudioControllerManager.setVolume(definition.id, value)
        );
    }

    private static Component captionLabel(Component caption, Double value) {
        return value == 0.0
                ? Options.genericValueLabel(caption, CommonComponents.OPTION_OFF)
                : Component.translatable("options.percent_value", caption, (int) (value * 100.0));
    }
}
