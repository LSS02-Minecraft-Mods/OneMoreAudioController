package net.fancymenuaddon.onemoreaudiocontroller.api;

import net.fancymenuaddon.onemoreaudiocontroller.AudioControllerManager;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.List;

/**
 * Public API for other mods to add their own audio controller - a new independent slider on the
 * vanilla "Music &amp; Sounds" options screen - entirely from code, with no JSON file to ship.
 *
 * <p>Example: a guns mod wants its own "Gun Sounds" slider, independent from Players/Blocks/etc:
 * <pre>{@code
 * OneMoreAudioControllerApi.registerController(
 *         "mygunmod_gun_sounds",
 *         new ResourceLocation("mygunmod", "gun_shot"),
 *         new ResourceLocation("mygunmod", "gun_reload")
 * );
 * }</pre>
 *
 * <p>Call this once, early - a mod constructor or {@code FMLCommonSetupEvent} both work, since the
 * final on-screen order is only computed the first time the player opens the Sound Options screen,
 * well after every mod has finished loading.
 *
 * <p>Controllers registered this way are mirrored (id, sounds, current volume) to
 * {@code config/onemoreaudiocontroller/externalcontroller.json} on every game start, purely so
 * modpack authors editing {@code controllers.json} by hand can see which ids are already taken and
 * avoid picking the same one. That file is never read back as a source of controllers - only your
 * code is. If a JSON controller and an API controller ever share an id, the API one always wins and
 * the JSON one is skipped with a warning in the log.
 *
 * <p>Pick an id that includes your own mod id (e.g. {@code "mygunmod_gun_sounds"}) to avoid clashing
 * with other mods or with this mod's own reserved vanilla category names
 * (master/music/records/weather/blocks/hostile/neutral/players/ambient/voice).
 */
public final class OneMoreAudioControllerApi {

    private OneMoreAudioControllerApi() {
    }

    /**
     * Registers a controller whose on-screen label comes from the translation key
     * {@code "soundCategory." + id} (add that key to your mod's own lang files).
     *
     * @param id     unique id for this controller, see the class doc for naming advice
     * @param sounds every sound event this slider should control the volume of
     */
    public static void registerController(String id, ResourceLocation... sounds) {
        registerController(id, "soundCategory." + id, sounds);
    }

    /**
     * Registers a controller with an explicit translation key for its on-screen label.
     *
     * @param id             unique id for this controller, see the class doc for naming advice
     * @param translationKey translation key resolved for the slider's label (add it to your lang files)
     * @param sounds         every sound event this slider should control the volume of
     */
    public static void registerController(String id, String translationKey, ResourceLocation... sounds) {
        List<ResourceLocation> soundList = sounds == null ? List.of() : Arrays.asList(sounds);
        AudioControllerManager.registerApiController(id, translationKey, soundList);
    }
}
