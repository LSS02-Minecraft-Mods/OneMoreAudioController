package net.fancymenuaddon.onemoreaudiocontroller.client;

import net.fancymenuaddon.onemoreaudiocontroller.AudioControllerManager;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Builds a vanilla-styled slider {@link OptionInstance} for a JSON- or API-defined custom controller. */
public final class CustomSoundOptions {

    private CustomSoundOptions() {
    }

    public static OptionInstance<Double> build(AudioControllerManager.ControllerDefinition definition) {
        // OptionInstance's own constructor turns the translation key into a plain
        // Component.translatable(...) internally, which would render the raw, ugly key when no
        // lang entry exists. We ignore that and build the label ourselves with a fallback, so an
        // untranslated controller still shows its English default_name/defaultName instead.
        Component label = Component.translatableWithFallback(definition.translationKey, definition.defaultName);

        return new OptionInstance<>(
                definition.translationKey,
                OptionInstance.noTooltip(),
                (ignoredCaption, value) -> captionLabel(label, value),
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
