package net.ngsh.shydevelopment.onemoreaudiocontroller.runtime;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.Map;

/**
 * Adds (or, on a rename, rebuilds) a working per-category volume slider on an already-running
 * {@link Options} instance for a {@link SoundSource} that didn't exist yet when that {@code Options}
 * was built - i.e. every controller promoted to a real {@code SoundSource} after game boot (API
 * controllers registered by other mods, or controllers added in-game through this mod's Controller
 * Manager screen).
 *
 * <p>{@code Options} keeps its vanilla sliders in a {@code Map<SoundSource, OptionInstance<Double>>}
 * backed by an {@link EnumMap}, whose internal array is sized once from
 * {@code SoundSource.values()} at construction time and never grows afterwards - even once
 * {@link SoundSourceEnumInjector} has patched the enum itself, calling
 * {@code Options.getSoundSourceVolume(...)} for the new constant would throw. This builds a fresh,
 * correctly-sized replacement map (now that the enum patch makes {@code SoundSource.values()}
 * report the new constant too) and swaps it onto the live instance.
 *
 * <p>The slider itself is built here rather than through vanilla's own private
 * {@code Options.createSoundSliderOptionInstance}: that factory turns its label straight into
 * {@code Component.translatable(key)} with no fallback, so a controller with no matching lang entry
 * (the normal case for a custom one) would show the raw key ({@code soundCategory.prova}) instead of
 * its {@code default_name}. {@link net.ngsh.shydevelopment.onemoreaudiocontroller.client.CustomSoundOptions}
 * already solves exactly this for non-promoted sliders with {@code translatableWithFallback}; this
 * mirrors that, wired to vanilla's own {@code SoundManager} instead so live volume changes still
 * apply immediately and persist to {@code options.txt} exactly like a real vanilla category.
 */
public final class OptionsSoundSourcePatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Unsafe UNSAFE = UnsafeAccess.INSTANCE;

    private OptionsSoundSourcePatcher() {
    }

    /**
     * Makes sure {@code source} has a slider showing {@code defaultName}, creating one if needed or
     * rebuilding it (same volume, new label) if it already exists - so renaming a promoted
     * controller, or deleting and recreating one under the same id, is reflected immediately instead
     * of only after a restart. {@code OptionInstance} has no way to change its label after
     * construction, so "rebuilding" always means swapping in a new instance; {@code fallbackVolume}
     * is only used the first time a slider is created for {@code source}; on every later call its
     * current live value is read back and carried over instead, so this never resets a volume the
     * player already set.
     */
    @SuppressWarnings("unchecked")
    public static synchronized void syncSlider(Options options, SoundSource source, String defaultName, double fallbackVolume) {
        if (UNSAFE == null) {
            return;
        }
        try {
            Field mapField = findSoundSourceVolumesField();
            long mapOffset = UNSAFE.objectFieldOffset(mapField);
            Map<SoundSource, OptionInstance<Double>> current =
                    (Map<SoundSource, OptionInstance<Double>>) UNSAFE.getObject(options, mapOffset);

            // Neither current.containsKey(source)/get(source) nor a fresh EnumMap's putAll(current)
            // is safe to call here: both special-case same-key-type EnumMaps and index straight into
            // the *other* map's backing array by ordinal - current's array is still sized to whatever
            // SoundSource.values() reported when `options` was built, so indexing it with a just-
            // injected constant's (necessarily higher) ordinal throws ArrayIndexOutOfBoundsException.
            // Copying entry-by-entry through the public Map API only ever touches each map's own,
            // internally-consistent bounds, so it's the one safe way to move entries across. Only
            // *after* that copy is `rebuilt` safe to query by `source` (it was sized fresh, from the
            // already-patched enum, so `source`'s ordinal is guaranteed to be in range).
            EnumMap<SoundSource, OptionInstance<Double>> rebuilt = new EnumMap<>(SoundSource.class);
            for (Map.Entry<SoundSource, OptionInstance<Double>> entry : current.entrySet()) {
                rebuilt.put(entry.getKey(), entry.getValue());
            }

            OptionInstance<Double> existing = rebuilt.get(source);
            double volume = existing != null ? existing.get() : fallbackVolume;
            rebuilt.put(source, buildSlider(source, defaultName, volume));

            UNSAFE.putObject(options, mapOffset, rebuilt);
            LOGGER.info("[onemoreaudiocontroller] Synced the live Music & Sounds slider for '{}'", source.getName());
        } catch (ReflectiveOperationException e) {
            LOGGER.error("[onemoreaudiocontroller] Failed to sync the live volume slider for SoundSource '{}'; " +
                    "it'll fall back to this mod's own slider", source.getName(), e);
        }
    }

    /**
     * {@code Options}'s own {@code soundSourceVolumes} field, found by type rather than by that
     * literal name: the same literal-name lookup approach in {@code SoundSourceEnumInjector} was
     * confirmed (via a real game log, not just in theory) to throw {@code NoSuchFieldException} at
     * runtime even though the exact same name resolves fine against the mapped dev jar - see that
     * class's {@code findNameField()} for the full story. {@code Options} declares exactly one field
     * of type {@code Map}, so matching on that is unambiguous.
     */
    private static Field findSoundSourceVolumesField() throws NoSuchFieldException {
        for (Field field : Options.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())) {
                return field;
            }
        }
        throw new NoSuchFieldException("Options soundSourceVolumes field");
    }

    private static OptionInstance<Double> buildSlider(SoundSource source, String defaultName, double initialVolume) {
        Component label = Component.translatableWithFallback("soundCategory." + source.getName(), defaultName);
        return new OptionInstance<>(
                "soundCategory." + source.getName(),
                OptionInstance.noTooltip(),
                (ignoredCaption, value) -> captionLabel(label, value),
                OptionInstance.UnitDouble.INSTANCE,
                initialVolume,
                value -> Minecraft.getInstance().getSoundManager().updateSourceVolume(source, value.floatValue())
        );
    }

    private static Component captionLabel(Component caption, Double value) {
        return value == 0.0
                ? Options.genericValueLabel(caption, CommonComponents.OPTION_OFF)
                : Component.translatable("options.percent_value", caption, (int) (value * 100.0));
    }
}
