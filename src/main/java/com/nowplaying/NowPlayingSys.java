package com.nowplaying;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NowPlayingSys implements ModInitializer {
    public static final String MOD_ID = "nowplayingsys";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Media polling is client-only and starts when the Now Playing screen opens (see NowPlayingScreen).
        LOGGER.info("NowPlayingSys initialized.");
    }
}