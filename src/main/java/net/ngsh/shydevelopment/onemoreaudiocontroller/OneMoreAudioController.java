package net.ngsh.shydevelopment.onemoreaudiocontroller;

import net.ngsh.shydevelopment.onemoreaudiocontroller.client.gui.ControllerManagerScreen;
import net.ngsh.shydevelopment.onemoreaudiocontroller.runtime.GeneratedTranslationPack;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(OneMoreAudioController.MODID)
public class OneMoreAudioController {

    public static final String MODID = "onemoreaudiocontroller";

    public OneMoreAudioController(FMLJavaModLoadingContext context) {
        context.getModEventBus().addListener(GeneratedTranslationPack::addPackFinders);

        AudioControllerManager.reload();
        AudioControllerManager.promoteConfiguredControllersAtBoot();

        // Lets external mod-list GUIs (Catalogue, Forge's own Mods screen "Config" button, ...)
        // open a settings screen for this mod. We open our own controller manager screen, which
        // lets the player add/rename/delete/reorder controllers in-game, with a shortcut from there
        // into the vanilla Sound Options screen to actually move the sliders.
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, screen) -> new ControllerManagerScreen(screen))
        );
    }
}
