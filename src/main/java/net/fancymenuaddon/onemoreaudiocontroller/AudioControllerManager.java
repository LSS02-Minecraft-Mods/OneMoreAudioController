package net.fancymenuaddon.onemoreaudiocontroller;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Holds every audio controller known to the mod, from two sources:
 * <ul>
 *   <li><b>JSON</b> - hand/modpack-edited entries in {@code config/onemoreaudiocontroller/controllers.json}.
 *   Reloaded from disk every time {@link #reload()} runs.</li>
 *   <li><b>API</b> - entries other mods add in code via
 *   {@link net.fancymenuaddon.onemoreaudiocontroller.api.OneMoreAudioControllerApi}. These are
 *   mirrored to {@code config/onemoreaudiocontroller/externalcontroller.json} purely so modpack
 *   authors editing controllers.json can see which ids are already taken by mod code - that file
 *   is never read as an *input* for defining controllers, only for restoring saved volumes and
 *   reporting taken ids.</li>
 * </ul>
 * If both sources define the same id, the API entry wins and the JSON one is skipped with a
 * warning. Final display order is only computed once, in {@link #recompute()}, after both sources
 * have contributed: entries from {@code order.json} come first (in that order), then everything
 * else (remaining vanilla categories, then remaining controllers) is appended.
 */
public final class AudioControllerManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String DEFAULT_CONTROLLERS = """
            [
              {
                "id": "menu_music",
                "sounds": ["minecraft:music.menu"]
              }
            ]
            """;

    private static final String DEFAULT_ORDER = """
            [
              "music", "menu_music",
              "records", "voice",
              "ambient", "weather",
              "hostile", "neutral",
              "players", "blocks"
            ]
            """;

    // ---- Layer 1: JSON (controllers.json / order.json), refreshed by reload() ----
    private static volatile Map<String, ControllerDefinition> jsonControllers = Map.of();
    private static volatile List<String> orderFileEntries = List.of();

    // ---- Layer 2: API (registerController), accumulated in memory, mirrored to externalcontroller.json ----
    private static final Map<String, ControllerDefinition> apiControllers = new LinkedHashMap<>();
    private static Map<String, Double> persistedApiVolumes;

    // ---- Merged, ready-to-render snapshot. Recomputed whenever either layer above changes. ----
    private static volatile Map<String, ControllerDefinition> mergedById = Map.of();
    private static volatile Map<ResourceLocation, ControllerDefinition> mergedBySound = Map.of();
    private static volatile List<String> finalOrder = List.of();

    private AudioControllerManager() {
    }

    public static final class ControllerDefinition {
        public final String id;
        public final String translationKey;
        public final Set<ResourceLocation> sounds;
        public volatile double volume;

        private ControllerDefinition(String id, String translationKey, Set<ResourceLocation> sounds, double volume) {
            this.id = id;
            this.translationKey = translationKey;
            this.sounds = sounds;
            this.volume = volume;
        }
    }

    private static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve("onemoreaudiocontroller");
    }

    private static Path controllersFile() {
        return configDir().resolve("controllers.json");
    }

    private static Path orderFile() {
        return configDir().resolve("order.json");
    }

    private static Path externalControllersFile() {
        return configDir().resolve("externalcontroller.json");
    }

    /** Re-reads controllers.json and order.json from disk. Safe to call repeatedly (e.g. every time the Sound Options screen opens). */
    public static synchronized void reload() {
        try {
            Files.createDirectories(configDir());
            ensureDefault(controllersFile(), DEFAULT_CONTROLLERS);
            ensureDefault(orderFile(), DEFAULT_ORDER);
        } catch (IOException e) {
            LOGGER.error("[onemoreaudiocontroller] Failed to create default config files", e);
            return;
        }

        Map<String, ControllerDefinition> newJsonControllers = new LinkedHashMap<>();
        try {
            JsonArray controllersJson = JsonParser.parseString(Files.readString(controllersFile())).getAsJsonArray();
            for (JsonElement element : controllersJson) {
                ControllerDefinition definition = parseController(element);
                if (definition == null) {
                    continue;
                }
                if (newJsonControllers.containsKey(definition.id)) {
                    LOGGER.warn("[onemoreaudiocontroller] Duplicate controller id '{}' in controllers.json, keeping the first one", definition.id);
                    continue;
                }
                newJsonControllers.put(definition.id, definition);
            }
        } catch (Exception e) {
            LOGGER.error("[onemoreaudiocontroller] Failed to read controllers.json, no JSON controllers will be available", e);
        }

        List<String> newOrderFileEntries = new ArrayList<>();
        try {
            JsonArray orderJson = JsonParser.parseString(Files.readString(orderFile())).getAsJsonArray();
            for (JsonElement element : orderJson) {
                newOrderFileEntries.add(element.getAsString());
            }
        } catch (Exception e) {
            LOGGER.error("[onemoreaudiocontroller] Failed to read order.json", e);
        }

        jsonControllers = Map.copyOf(newJsonControllers);
        orderFileEntries = List.copyOf(newOrderFileEntries);
        recompute();
    }

    /**
     * Registers (or redefines) a controller in code. See
     * {@link net.fancymenuaddon.onemoreaudiocontroller.api.OneMoreAudioControllerApi} for the
     * public entry point - mods should not call this class directly.
     */
    public static synchronized void registerApiController(String id, String translationKey, Collection<ResourceLocation> sounds) {
        if (id == null || id.isBlank()) {
            LOGGER.error("[onemoreaudiocontroller] Ignoring registerController() call with a blank id");
            return;
        }
        if (isReservedId(id)) {
            LOGGER.error("[onemoreaudiocontroller] Ignoring registerController('{}', ...): that id clashes with a vanilla sound category", id);
            return;
        }
        if (sounds == null || sounds.isEmpty()) {
            LOGGER.warn("[onemoreaudiocontroller] registerController('{}', ...) was called with no sounds; its slider will have nothing to control", id);
        }

        ensurePersistedVolumesLoaded();
        double volume;
        ControllerDefinition existing = apiControllers.get(id);
        if (existing != null) {
            LOGGER.info("[onemoreaudiocontroller] Controller '{}' was already registered via the API, redefining it", id);
            volume = existing.volume;
        } else {
            volume = persistedApiVolumes.getOrDefault(id, 1.0);
        }

        String resolvedTranslationKey = translationKey == null || translationKey.isBlank() ? "soundCategory." + id : translationKey;
        Set<ResourceLocation> soundSet = sounds == null ? Set.of() : Set.copyOf(new HashSet<>(sounds));
        apiControllers.put(id, new ControllerDefinition(id, resolvedTranslationKey, soundSet, volume));

        saveExternalControllers();
        recompute();
    }

    private static void ensurePersistedVolumesLoaded() {
        if (persistedApiVolumes != null) {
            return;
        }
        persistedApiVolumes = new HashMap<>();
        Path file = externalControllersFile();
        if (Files.notExists(file)) {
            return;
        }
        try {
            JsonArray array = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("id") && obj.has("volume")) {
                    persistedApiVolumes.put(obj.get("id").getAsString(), Mth.clamp(obj.get("volume").getAsDouble(), 0.0, 1.0));
                }
            }
        } catch (Exception e) {
            LOGGER.error("[onemoreaudiocontroller] Failed to read externalcontroller.json, saved volumes for API controllers will reset", e);
        }
    }

    /** Merges both layers into the snapshot the mixins actually read. API entries win on id collisions. */
    private static synchronized void recompute() {
        Map<String, ControllerDefinition> merged = new LinkedHashMap<>(apiControllers);
        for (ControllerDefinition definition : jsonControllers.values()) {
            if (merged.containsKey(definition.id)) {
                LOGGER.warn("[onemoreaudiocontroller] controllers.json defines id '{}' but a mod already registered it via the API; " +
                        "the JSON entry is ignored. Check externalcontroller.json for taken ids.", definition.id);
                continue;
            }
            merged.put(definition.id, definition);
        }

        List<String> newOrder = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String rawId : orderFileEntries) {
            if (rawId.equalsIgnoreCase("master")) {
                continue; // Master always stays first and outside this list, see SoundOptionsScreenMixin.
            }
            boolean known = isVanillaNonMasterCategory(rawId) || merged.containsKey(rawId);
            if (!known) {
                LOGGER.warn("[onemoreaudiocontroller] Unknown id '{}' in order.json, skipping", rawId);
                continue;
            }
            if (seen.add(rawId)) {
                newOrder.add(rawId);
            }
        }
        // Everything left over is appended afterwards: remaining vanilla categories first, then remaining controllers.
        for (SoundSource source : SoundSource.values()) {
            if (source == SoundSource.MASTER) {
                continue;
            }
            String id = source.name().toLowerCase(Locale.ROOT);
            if (seen.add(id)) {
                newOrder.add(id);
            }
        }
        for (String id : merged.keySet()) {
            if (seen.add(id)) {
                newOrder.add(id);
            }
        }

        Map<ResourceLocation, ControllerDefinition> bySound = new HashMap<>();
        for (ControllerDefinition definition : merged.values()) {
            for (ResourceLocation location : definition.sounds) {
                bySound.put(location, definition);
            }
        }

        mergedById = Map.copyOf(merged);
        mergedBySound = Map.copyOf(bySound);
        finalOrder = List.copyOf(newOrder);
    }

    private static boolean isVanillaNonMasterCategory(String id) {
        try {
            return SoundSource.valueOf(id.toUpperCase(Locale.ROOT)) != SoundSource.MASTER;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ControllerDefinition parseController(JsonElement element) {
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has("id") || !obj.has("sounds")) {
            LOGGER.warn("[onemoreaudiocontroller] Skipping controller entry missing 'id' or 'sounds': {}", obj);
            return null;
        }
        String id = obj.get("id").getAsString();
        if (isReservedId(id)) {
            LOGGER.warn("[onemoreaudiocontroller] Controller id '{}' clashes with a vanilla sound category, skipping", id);
            return null;
        }
        String translationKey = obj.has("translationKey") ? obj.get("translationKey").getAsString() : "soundCategory." + id;
        double volume = obj.has("volume") ? Mth.clamp(obj.get("volume").getAsDouble(), 0.0, 1.0) : 1.0;

        Set<ResourceLocation> sounds = new HashSet<>();
        for (JsonElement soundElement : obj.getAsJsonArray("sounds")) {
            String raw = soundElement.getAsString();
            ResourceLocation location = ResourceLocation.tryParse(raw);
            if (location == null) {
                LOGGER.warn("[onemoreaudiocontroller] Invalid sound id '{}' for controller '{}', skipping that entry", raw, id);
                continue;
            }
            sounds.add(location);
        }

        return new ControllerDefinition(id, translationKey, Set.copyOf(sounds), volume);
    }

    private static boolean isReservedId(String id) {
        try {
            SoundSource.valueOf(id.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void ensureDefault(Path file, String content) throws IOException {
        if (Files.notExists(file)) {
            Files.writeString(file, content);
        }
    }

    public static ControllerDefinition controllerById(String id) {
        return mergedById.get(id);
    }

    public static ControllerDefinition findBySound(ResourceLocation location) {
        return mergedBySound.get(location);
    }

    public static List<String> order() {
        return finalOrder;
    }

    public static void setVolume(String id, double volume) {
        ControllerDefinition definition = mergedById.get(id);
        if (definition == null) {
            return;
        }
        definition.volume = Mth.clamp(volume, 0.0, 1.0);
        if (apiControllers.containsKey(id)) {
            saveExternalControllers();
        } else {
            saveJsonControllers();
        }
    }

    private static synchronized void saveJsonControllers() {
        JsonArray array = new JsonArray();
        for (ControllerDefinition definition : jsonControllers.values()) {
            array.add(toJson(definition));
        }
        try {
            Files.createDirectories(configDir());
            Files.writeString(controllersFile(), GSON.toJson(array));
        } catch (IOException e) {
            LOGGER.error("[onemoreaudiocontroller] Failed to save controllers.json", e);
        }
    }

    private static synchronized void saveExternalControllers() {
        JsonArray array = new JsonArray();
        for (ControllerDefinition definition : apiControllers.values()) {
            array.add(toJson(definition));
        }
        try {
            Files.createDirectories(configDir());
            Files.writeString(externalControllersFile(), GSON.toJson(array));
        } catch (IOException e) {
            LOGGER.error("[onemoreaudiocontroller] Failed to save externalcontroller.json", e);
        }
    }

    private static JsonObject toJson(ControllerDefinition definition) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", definition.id);
        if (!definition.translationKey.equals("soundCategory." + definition.id)) {
            obj.addProperty("translationKey", definition.translationKey);
        }
        JsonArray sounds = new JsonArray();
        definition.sounds.forEach(location -> sounds.add(location.toString()));
        obj.add("sounds", sounds);
        obj.addProperty("volume", definition.volume);
        return obj;
    }
}
