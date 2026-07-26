package net.fancymenuaddon.onemoreaudiocontroller.runtime;

import com.mojang.logging.LogUtils;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Adds brand new constants to the vanilla {@link SoundSource} enum at runtime, so that other mods
 * see this mod's controllers as genuine sound categories instead of something only this mod's own
 * mixins know about. This matters because mods that let players pick a sound category (e.g.
 * FancyMenu's audio elements) do so by iterating the real {@code SoundSource.values()} - there is
 * no registry or extension point to hook into instead, only the actual enum.
 *
 * <p>Every consumer, vanilla or modded, that calls {@code SoundSource.values()} re-reads the
 * {@code $VALUES} array fresh on every call (it's a plain {@code return $VALUES.clone();}), so once
 * that array is patched here the new constants are visible everywhere immediately - the one
 * exception is {@link net.minecraft.client.Options}, which copies {@code values()} into a
 * fixed-size map once at construction time; see {@link OptionsSoundSourcePatcher} for that case.
 */
public final class SoundSourceEnumInjector {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Unsafe UNSAFE = UnsafeAccess.INSTANCE;

    private static final Object LOCK = new Object();
    private static final Map<String, SoundSource> INJECTED = new LinkedHashMap<>();

    private SoundSourceEnumInjector() {
    }

    /**
     * Adds one new {@link SoundSource} constant per id not already present in the enum, skipping
     * (and returning as-is) any id already injected by an earlier call. Safe to call repeatedly
     * with overlapping ids, and safe to call both before and after {@code Options}/{@code Minecraft}
     * exist.
     *
     * @return every id that is now a real {@link SoundSource} - either just injected, or injected
     *         by a previous call. An id is missing from the result only if {@code Unsafe} isn't
     *         available, if it isn't a valid Java identifier once uppercased, or if the injection
     *         itself failed (always logged, never thrown).
     */
    public static Map<String, SoundSource> injectMissing(Collection<String> ids) {
        if (UNSAFE == null) {
            return Map.of();
        }
        synchronized (LOCK) {
            Map<String, SoundSource> result = new LinkedHashMap<>();
            for (String id : ids) {
                SoundSource existing = INJECTED.get(id);
                if (existing != null) {
                    result.put(id, existing);
                    continue;
                }

                String constantName = id.toUpperCase(Locale.ROOT);
                if (!isValidJavaIdentifier(constantName)) {
                    LOGGER.error("[onemoreaudiocontroller] Can't expose controller '{}' as a real SoundSource: " +
                            "'{}' isn't a valid Java identifier, it'll stay a mod-only channel", id, constantName);
                    continue;
                }

                try {
                    SoundSource injected = injectOne(constantName, id);
                    INJECTED.put(id, injected);
                    result.put(id, injected);
                } catch (Throwable t) {
                    LOGGER.error("[onemoreaudiocontroller] Failed to turn controller '{}' into a real SoundSource, " +
                            "it'll stay a mod-only channel", id, t);
                }
            }
            return result;
        }
    }

    /** The real {@link SoundSource} an id was injected as, or {@code null} if it never was. */
    public static SoundSource get(String id) {
        synchronized (LOCK) {
            return INJECTED.get(id);
        }
    }

    private static boolean isValidJavaIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static SoundSource injectOne(String constantName, String serializedName) throws ReflectiveOperationException {
        // This runs as early as right before Options is built (see MinecraftMixin), possibly before
        // anything has ever actively used SoundSource - and an unused class's static fields, $VALUES
        // included, stay null until its <clinit> actually runs. SoundSource.class alone (used below
        // by findValuesField()) only guarantees the class is *loaded*, not *initialized*; values()
        // is a real active use, so calling it first guarantees $VALUES is populated before the
        // Unsafe read a few lines down, which would otherwise NPE on a still-null array.
        SoundSource.values();

        Field valuesField = findValuesField();
        Object valuesBase = UNSAFE.staticFieldBase(valuesField);
        long valuesOffset = UNSAFE.staticFieldOffset(valuesField);
        SoundSource[] current = (SoundSource[]) UNSAFE.getObject(valuesBase, valuesOffset);

        SoundSource instance = (SoundSource) UNSAFE.allocateInstance(SoundSource.class);
        setEnumIdentity(instance, constantName, current.length);
        setSerializedName(instance, serializedName);

        SoundSource[] extended = Arrays.copyOf(current, current.length + 1);
        extended[current.length] = instance;
        UNSAFE.putObject(valuesBase, valuesOffset, extended);

        resetClassEnumCaches();

        LOGGER.info("[onemoreaudiocontroller] Controller '{}' is now a real Minecraft sound category (SoundSource.{})",
                serializedName, constantName);
        return instance;
    }

    /** The compiler-synthesized backing array for {@code values()} - always present on every enum, its exact
     *  field name (conventionally {@code $VALUES}) isn't remapped by any mapping set since javac generates it. */
    private static Field findValuesField() throws NoSuchFieldException {
        for (Field field : SoundSource.class.getDeclaredFields()) {
            if (field.isSynthetic() && field.getType() == SoundSource[].class) {
                return field;
            }
        }
        throw new NoSuchFieldException("SoundSource $VALUES field");
    }

    private static void setEnumIdentity(SoundSource instance, String name, int ordinal) throws NoSuchFieldException {
        Field nameField = Enum.class.getDeclaredField("name");
        UNSAFE.putObject(instance, UNSAFE.objectFieldOffset(nameField), name);
        Field ordinalField = Enum.class.getDeclaredField("ordinal");
        UNSAFE.putInt(instance, UNSAFE.objectFieldOffset(ordinalField), ordinal);
    }

    /** {@code SoundSource}'s own serialized-name field (what {@code getName()} returns) - unrelated to
     *  {@code Enum}'s own {@code name} field even though they share a field name; Java allows that fine. */
    private static void setSerializedName(SoundSource instance, String serializedName) throws NoSuchFieldException {
        Field nameField = SoundSource.class.getDeclaredField("name");
        UNSAFE.putObject(instance, UNSAFE.objectFieldOffset(nameField), serializedName);
    }

    /** Clears {@code Class}'s cached {@code values()}/{@code valueOf()} lookup tables so they're rebuilt from the patched array. */
    private static void resetClassEnumCaches() {
        try {
            Field enumConstants = Class.class.getDeclaredField("enumConstants");
            UNSAFE.putObject(SoundSource.class, UNSAFE.objectFieldOffset(enumConstants), null);
            Field enumConstantDirectory = Class.class.getDeclaredField("enumConstantDirectory");
            UNSAFE.putObject(SoundSource.class, UNSAFE.objectFieldOffset(enumConstantDirectory), null);
        } catch (NoSuchFieldException e) {
            LOGGER.warn("[onemoreaudiocontroller] Couldn't reset SoundSource's cached enum lookup tables; " +
                    "SoundSource.valueOf(...) may not see the new constant until something else clears it", e);
        }
    }
}
