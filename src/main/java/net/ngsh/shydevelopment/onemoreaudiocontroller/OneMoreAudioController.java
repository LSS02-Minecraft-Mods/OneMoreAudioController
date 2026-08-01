package net.ngsh.shydevelopment.onemoreaudiocontroller;

import net.ngsh.shydevelopment.onemoreaudiocontroller.client.gui.ControllerManagerScreen;
import net.ngsh.shydevelopment.onemoreaudiocontroller.runtime.GeneratedTranslationPack;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(OneMoreAudioController.MODID)
public class OneMoreAudioController {

    public static final String MODID = "onemoreaudiocontroller";

    public OneMoreAudioController(FMLJavaModLoadingContext context) {
        context.getModEventBus().addListener(GeneratedTranslationPack::addPackFinders);
        context.getModEventBus().addListener(OneMoreAudioController::onClientSetup);

        // Pure disk I/O (reads controllers.json/orders.json, writes the generated lang file) - no
        // Minecraft/Options dependency, so it's safe here, and it has to run this early: it needs to
        // be done before Minecraft's own "initial" resource-pack load (which happens shortly after
        // this constructor returns, while the loading screen is still up) so that load picks up the
        // freshly-written translations for free, with no extra reload call needed at all - see
        // GeneratedTranslationPack.regenerate() for why forcing one here specifically caused crashes.
        AudioControllerManager.reload();

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

    // promoteConfiguredControllersAtBoot() touches Minecraft.options/SoundSource, so it stays
    // deferred past mod construction to FMLClientSetupEvent's enqueueWork(), once client setup's
    // parallel mod work is done and Minecraft is reliably fully set up - see that method's own doc
    // for why this specific piece needs that later, safer point. It doesn't write the resource pack
    // or trigger a reload, so - unlike AudioControllerManager.reload() - it has no reason to run any
    // earlier than this.
    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(AudioControllerManager::promoteConfiguredControllersAtBoot);
    }
}
