package net.ngsh.shydevelopment.onemoreaudiocontroller.runtime;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Shared {@link Unsafe} handle used to graft real {@link net.minecraft.sounds.SoundSource}
 * constants onto the vanilla enum at runtime. {@code Unsafe} lives in the {@code jdk.unsupported}
 * module, which the JVM keeps open to every caller by design (specifically to keep this kind of
 * legacy-compatibility trick working), so grabbing it needs no {@code --add-opens} JVM flag -
 * unlike plain reflection on {@code java.lang.Enum}/{@code java.lang.Class} internals, which is
 * exactly why this mod uses {@code Unsafe} field writes instead of {@code Field#setAccessible}
 * for anything declared inside {@code java.base}.
 */
final class UnsafeAccess {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final Unsafe INSTANCE = resolve();

    private UnsafeAccess() {
    }

    private static Unsafe resolve() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("[onemoreaudiocontroller] sun.misc.Unsafe isn't available; custom controllers " +
                    "won't be exposed as real SoundSource categories to other mods (e.g. FancyMenu), " +
                    "they'll stay mod-only channels", e);
            return null;
        }
    }
}
