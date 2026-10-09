package com.nowplaying;

import com.mojang.blaze3d.platform.InputConstants;
import com.nowplaying.gui.NowPlayingScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * F8 toggles the Now Playing screen from anywhere (in a world, title screen, menus).
 * The key is polled each tick rather than registered as a KeyMapping, because key mappings only fire while
 * no screen is open.
 * <p>
 * Targets Minecraft 26.1.x: the current screen lives on {@code Minecraft.screen}, screens are opened with
 * {@code Minecraft.setScreen}, and {@code InputConstants.isKeyDown} takes a {@code Window}.
 */
public class NowPlayingClient implements ClientModInitializer {
    private static boolean f8WasDown;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean f8Down = InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_F8);
            if (f8Down && !f8WasDown) {
                toggleScreen(client);
            }
            f8WasDown = f8Down;
        });
    }

    private static void toggleScreen(Minecraft minecraft) {
        Screen current = minecraft.screen;
        if (current instanceof NowPlayingScreen) {
            minecraft.setScreen(null);
        } else if (!(current instanceof ChatScreen)) { // don't hijack F8 while typing in chat
            minecraft.setScreen(new NowPlayingScreen());
        }
    }
}