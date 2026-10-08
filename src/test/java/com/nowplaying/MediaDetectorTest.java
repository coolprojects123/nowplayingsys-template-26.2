package com.nowplaying;

import com.nowplaying.MediaDetector.MediaMetadata;
import com.nowplaying.MediaDetector.PlaybackState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaDetectorTest {
    private static final String SAMPLE = """
            method return time=1 sender=:1.5 -> destination=:1.99 serial=7 reply_serial=2
               variant       array [
                  dict entry(
                     string "mpris:length"
                     variant             uint64 215000000
                  )
                  dict entry(
                     string "mpris:artUrl"
                     variant             string "https://i.scdn.co/image/ab67616d"
                  )
                  dict entry(
                     string "xesam:artist"
                     variant             array [
                           string "Daft Punk"
                           string "Pharrell Williams"
                        ]
                  )
                  dict entry(
                     string "xesam:title"
                     variant             string "Get Lucky (feat. "Nile" Rodgers)"
                  )
               ]
            """;

    @Test
    void parsesMetadata() {
        assertEquals("Get Lucky (feat. \"Nile\" Rodgers)", MediaDetector.extractTitle(SAMPLE));
        assertEquals("Daft Punk, Pharrell Williams", MediaDetector.extractArtist(SAMPLE));
        assertEquals("https://i.scdn.co/image/ab67616d", MediaDetector.extractArtUrl(SAMPLE));
        assertEquals(215_000_000L, MediaDetector.extractLength(SAMPLE));
    }

    @Test
    void missingFieldsAreEmpty() {
        assertEquals("", MediaDetector.extractTitle("nothing here"));
        assertEquals("", MediaDetector.extractArtist("nothing here"));
        assertEquals(-1L, MediaDetector.extractLength("nothing here"));
    }

    @Test
    void displayNameStripsPrefixAndInstance() {
        assertEquals("Spotify", MediaDetector.displayName("org.mpris.MediaPlayer2.spotify"));
        assertEquals("Firefox", MediaDetector.displayName("org.mpris.MediaPlayer2.firefox.instance1234"));
    }

    @Test
    void progressExtrapolatesOnlyWhilePlaying() {
        MediaMetadata paused = new MediaMetadata("x", "t", "a", "", PlaybackState.PAUSED, 50L, 100L, 0);
        assertEquals(0.5f, paused.progress());

        MediaMetadata playing = new MediaMetadata("x", "t", "a", "", PlaybackState.PLAYING, 50_000_000L,
                100_000_000L, System.nanoTime());
        assertTrue(playing.progress() >= 0.5f);

        assertEquals(1.0f, new MediaMetadata("x", "t", "a", "", PlaybackState.PAUSED, 999L, 100L, 0).progress());
        assertEquals(0.0f, MediaMetadata.NONE.progress());
    }
}