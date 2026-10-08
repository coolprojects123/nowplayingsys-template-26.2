package com.nowplaying;

import com.mojang.blaze3d.platform.InputConstants;
import com.nowplaying.gui.NowPlayingScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class NowPlayingClient implements ClientModInitializer {
    /** Toggles the Now Playing screen. Rebindable under Options > Controls > Key Binds. */
    public static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        NowPlayingSys.LOGGER.info("[diag] onInitializeClient ran");
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(NowPlayingSys.MOD_ID, "main"));

        // 26.3 (SDL) removed InputConstants.Type.KEYSYM; the key code goes straight into the constructor.
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.nowplayingsys.open",
                InputConstants.KEY_F8,
                category));

        // Key mappings only fire while no screen is open, so this only ever opens the screen.
        // Closing with the same key is handled in NowPlayingScreen#keyPressed.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                NowPlayingSys.LOGGER.info("[diag] open key consumed, player={}", client.player);
                if (client.player != null) {
                    try {
                        client.gui.setScreen(new NowPlayingScreen());
                        NowPlayingSys.LOGGER.info("[diag] setScreen returned");
                    } catch (Throwable t) {
                        NowPlayingSys.LOGGER.warn("[diag] opening screen failed", t);
                    }
                }
            }
        });
    }
}