package com.nowplaying;

import com.mojang.blaze3d.platform.InputConstants;
import com.nowplaying.gui.NowPlayingScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * F8 toggles the Now Playing screen from anywhere (in a world, title screen, menus), like the 26.2 version.
 * The key is polled each tick rather than registered as a KeyMapping, because key mappings only fire while
 * no screen is open.
 */
public class NowPlayingClient implements ClientModInitializer {
    private static boolean f8WasDown;
    /** InputConstants.isKeyDown differs between versions (26.2: Window + key, SDL builds may be key only). */
    private static Method isKeyDownMethod;

    @Override
    public void onInitializeClient() {
        isKeyDownMethod = findIsKeyDown();
        if (isKeyDownMethod == null) {
            NowPlayingSys.LOGGER.warn("InputConstants.isKeyDown not found; the F8 hotkey will not work");
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean f8Down = isKeyDown(client, InputConstants.KEY_F8);
            if (f8Down && !f8WasDown) {
                toggleScreen(Minecraft.getInstance());
            }
            f8WasDown = f8Down;
        });
    }

    private static void toggleScreen(Minecraft minecraft) {
        Screen current = getCurrentScreen(minecraft);
        if (current instanceof NowPlayingScreen) {
            setCurrentScreen(minecraft, null);
        } else if (!(current instanceof ChatScreen)) { // don't hijack F8 while typing in chat
            setCurrentScreen(minecraft, new NowPlayingScreen());
        }
    }

    // ------------------------------------------------------------------ key polling

    private static Method findIsKeyDown() {
        for (Method m : InputConstants.class.getMethods()) {
            if (!m.getName().equals("isKeyDown") || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == int.class) {
                return m;
            }
            if (p.length == 2 && p[1] == int.class) {
                return m;
            }
        }
        return null;
    }

    private static boolean isKeyDown(Minecraft client, int key) {
        if (isKeyDownMethod == null) {
            return false;
        }
        try {
            if (isKeyDownMethod.getParameterCount() == 1) {
                return (boolean) isKeyDownMethod.invoke(null, key);
            }
            Object window = client.getClass().getMethod("getWindow").invoke(client);
            return (boolean) isKeyDownMethod.invoke(null, window, key);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ screen access

    private static Screen getCurrentScreen(Minecraft minecraft) {
        Object gui = minecraft.gui;
        if (gui != null) {
            try {
                Method screenMethod = gui.getClass().getMethod("screen");
                Object screen = screenMethod.invoke(gui);
                if (screen instanceof Screen s) {
                    return s;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall back to older client field access.
            }
        }

        try {
            Field screenField = minecraft.getClass().getField("screen");
            Object screen = screenField.get(minecraft);
            if (screen instanceof Screen s) {
                return s;
            }
        } catch (ReflectiveOperationException ignored) {
            // No compatible screen accessor exists on this runtime.
        }

        return null;
    }

    private static void setCurrentScreen(Minecraft minecraft, Screen screen) {
        try {
            minecraft.gui.setScreen(screen);
        } catch (Throwable t) {
            NowPlayingSys.LOGGER.warn("Could not open the Now Playing screen", t);
        }
    }
}