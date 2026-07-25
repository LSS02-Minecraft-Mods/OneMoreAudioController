package net.fancymenuaddon.onemoreaudiocontroller;

import net.minecraft.client.gui.screens.SoundOptionsScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(OneMoreAudioController.MODID)
public class OneMoreAudioController {

    public static final String MODID = "onemoreaudiocontroller";

    public OneMoreAudioController(FMLJavaModLoadingContext context) {
        AudioControllerManager.reload();

        // Lets external mod-list GUIs (Catalogue, Forge's own Mods screen "Config" button, ...)
        // open a settings screen for this mod. We reuse the vanilla Sound Options screen itself,
        // since that's where every slider - vanilla and JSON-defined - already lives and reloads live.
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, screen) -> new SoundOptionsScreen(screen, minecraft.options))
        );
    }
}
