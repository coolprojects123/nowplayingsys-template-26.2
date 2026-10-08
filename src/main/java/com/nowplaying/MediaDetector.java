package com.nowplaying;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the currently playing track. On Linux (and Minecraft running under Wine) this talks MPRIS
 * through {@code dbus-send}. Windows and macOS have basic, less-tested fallbacks.
 *
 * <p>Polling only runs between {@link #start()} and {@link #stop()}; the screen starts it when it opens
 * and stops it when it closes, so nothing is spawned while the screen is closed. All process work happens
 * off the render thread.
 */
public final class MediaDetector {
    private static final int COMMAND_TIMEOUT_SECONDS = 3;
    private static final int POLL_INTERVAL_SECONDS = 2;

    private static final String MPRIS_PREFIX = "org.mpris.MediaPlayer2.";
    private static final String MPRIS_PATH = "/org/mpris/MediaPlayer2";
    private static final String PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player";
    private static final Set<String> ALLOWED_COMMANDS = Set.of("Previous", "Next", "PlayPause");

    // dbus-send prints strings raw (quotes are not escaped), so capture greedily up to the last quote on the line.
    private static final Pattern BUS_NAME = Pattern.compile("\"(" + Pattern.quote(MPRIS_PREFIX) + "[^\"]+)\"");
    private static final Pattern TITLE = Pattern.compile(
            "string\\s+\"xesam:title\"\\s*\\n\\s*variant\\s+string\\s+\"(.*)\"\\s*$", Pattern.MULTILINE);
    private static final Pattern ART_URL = Pattern.compile(
            "string\\s+\"mpris:artUrl\"\\s*\\n\\s*variant\\s+string\\s+\"(.*)\"\\s*$", Pattern.MULTILINE);
    private static final Pattern LENGTH = Pattern.compile(
            "string\\s+\"mpris:length\"\\s*\\n\\s*variant\\s+u?int64\\s+(\\d+)");
    private static final Pattern ARTIST_ARRAY = Pattern.compile(
            "string\\s+\"xesam:artist\"\\s*\\n\\s*variant\\s+array\\s+\\[(.*?)\\n\\s*\\]", Pattern.DOTALL);
    private static final Pattern ARRAY_STRING = Pattern.compile("^\\s*string\\s+\"(.*)\"\\s*$", Pattern.MULTILINE);
    private static final Pattern STATUS = Pattern.compile("variant\\s+string\\s+\"(\\w+)\"");
    private static final Pattern POSITION = Pattern.compile("variant\\s+int64\\s+(-?\\d+)");

    public enum PlaybackState { PLAYING, PAUSED, STOPPED, UNKNOWN }

    /** Immutable snapshot of one poll. Position is as sampled; use {@link #progress()} for the live value. */
    public record MediaMetadata(String source, String title, String artist, String artUrl,
                                PlaybackState state, long positionMicros, long lengthMicros, long sampledAtNanos) {
        public static final MediaMetadata NONE =
                new MediaMetadata("None", "", "", "", PlaybackState.STOPPED, -1, -1, 0);

        public boolean hasTrack() {
            return title != null && !title.isBlank();
        }

        public boolean hasArtUrl() {
            return artUrl != null && !artUrl.isBlank();
        }

        public boolean isPlaying() {
            return state == PlaybackState.PLAYING;
        }

        public boolean hasTimeline() {
            return lengthMicros > 0 && positionMicros >= 0;
        }

        /** Playback progress in [0, 1], extrapolated from the last sample while playing. */
        public float progress() {
            if (!hasTimeline()) return 0f;
            long position = positionMicros;
            if (isPlaying()) {
                position += (System.nanoTime() - sampledAtNanos) / 1_000L;
            }
            return Math.clamp((float) position / (float) lengthMicros, 0f, 1f);
        }
    }

    private static final Object POLL_LOCK = new Object();
    private static final ExecutorService IO = Executors.newCachedThreadPool(daemonFactory("MediaDetector-IO"));

    private static volatile MediaMetadata currentMetadata = MediaMetadata.NONE;
    private static volatile String activeMprisPlayer = null;
    private static volatile ScheduledExecutorService scheduler;
    private static volatile boolean warnedMissingDbus = false;

    private MediaDetector() {
    }

    // ---------------------------------------------------------------- lifecycle

    /** Starts background polling. Safe to call repeatedly. */
    public static synchronized void start() {
        if (scheduler != null) return;
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(daemonFactory("MediaDetector-Poller"));
        s.scheduleWithFixedDelay(MediaDetector::pollSafely, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        scheduler = s;
    }

    /** Stops background polling. The last snapshot stays readable. */
    public static synchronized void stop() {
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) s.shutdownNow();
    }

    public static MediaMetadata getCurrentlyPlayingMetadata() {
        return currentMetadata;
    }

    /** Requests an immediate poll without blocking the caller. */
    public static void refreshAsync() {
        IO.execute(MediaDetector::pollSafely);
    }

    /** True when there is a player that playback commands can be sent to. */
    public static boolean canControl() {
        return activeMprisPlayer != null;
    }

    /** Sends Previous / Next / PlayPause to the active player without blocking, then re-polls. */
    public static void sendCommand(String method) {
        String player = activeMprisPlayer;
        if (player == null || !ALLOWED_COMMANDS.contains(method)) return;
        IO.execute(() -> {
            run(List.of("dbus-send", "--session", "--print-reply", "--dest=" + player,
                    MPRIS_PATH, PLAYER_INTERFACE + "." + method));
            pollSafely();
        });
    }

    // ---------------------------------------------------------------- polling

    private static void pollSafely() {
        synchronized (POLL_LOCK) {
            try {
                currentMetadata = poll();
            } catch (Exception e) {
                NowPlayingSys.LOGGER.debug("Media poll failed", e);
                currentMetadata = MediaMetadata.NONE;
            }
        }
    }

    private static MediaMetadata poll() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux") || (os.contains("win") && isRunningUnderWine())) {
            return pollLinux();
        }
        if (os.contains("win")) {
            return pollWindows();
        }
        if (os.contains("mac")) {
            return pollMac();
        }
        return MediaMetadata.NONE;
    }

    private static boolean isRunningUnderWine() {
        return System.getenv("WINEPREFIX") != null || System.getenv("WINE") != null
                || System.getenv("WINELOADER") != null;
    }

    // ---------------------------------------------------------------- Linux / MPRIS

    private static MediaMetadata pollLinux() {
        List<String[]> players = new ArrayList<>();
        for (String bus : listMprisPlayers()) {
            players.add(new String[]{bus, readStatus(bus)});
        }

        // Prefer a playing player (sticking with the current one), then paused ones, so two players don't flap.
        String active = activeMprisPlayer;
        players.sort(Comparator.comparingInt(p -> rank(p, active)));

        for (String[] player : players) {
            MediaMetadata metadata = readPlayer(player[0], player[1]);
            if (metadata.hasTrack()) {
                activeMprisPlayer = player[0];
                return metadata;
            }
        }
        activeMprisPlayer = null;
        return MediaMetadata.NONE;
    }

    private static int rank(String[] player, String active) {
        boolean isActive = player[0].equals(active);
        return switch (player[1]) {
            case "Playing" -> isActive ? 0 : 1;
            case "Paused" -> isActive ? 2 : 3;
            default -> 4;
        };
    }

    private static List<String> listMprisPlayers() {
        String output = run(List.of("dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.DBus",
                "/org/freedesktop/DBus", "org.freedesktop.DBus.ListNames"));
        List<String> names = new ArrayList<>();
        if (output == null) return names;
        Matcher m = BUS_NAME.matcher(output);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static String dbusGet(String bus, String property) {
        return run(List.of("dbus-send", "--session", "--print-reply", "--dest=" + bus, MPRIS_PATH,
                "org.freedesktop.DBus.Properties.Get", "string:" + PLAYER_INTERFACE, "string:" + property));
    }

    private static String readStatus(String bus) {
        String output = dbusGet(bus, "PlaybackStatus");
        Matcher m = output == null ? null : STATUS.matcher(output);
        return m != null && m.find() ? m.group(1) : "Stopped";
    }

    private static MediaMetadata readPlayer(String bus, String status) {
        String metadata = dbusGet(bus, "Metadata");
        if (metadata == null) return MediaMetadata.NONE;

        String title = extractTitle(metadata);
        if (title.isBlank()) return MediaMetadata.NONE;

        PlaybackState state = switch (status) {
            case "Playing" -> PlaybackState.PLAYING;
            case "Paused" -> PlaybackState.PAUSED;
            default -> PlaybackState.STOPPED;
        };

        long position = -1;
        String positionOutput = dbusGet(bus, "Position");
        Matcher pm = positionOutput == null ? null : POSITION.matcher(positionOutput);
        if (pm != null && pm.find()) position = Long.parseLong(pm.group(1));

        return new MediaMetadata(displayName(bus), title, extractArtist(metadata), extractArtUrl(metadata),
                state, position, extractLength(metadata), System.nanoTime());
    }

    /** "org.mpris.MediaPlayer2.firefox.instance1234" -> "Firefox". */
    static String displayName(String bus) {
        String name = bus.startsWith(MPRIS_PREFIX) ? bus.substring(MPRIS_PREFIX.length()) : bus;
        name = name.replaceFirst("\\.instance\\d+$", "");
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(dot + 1);
        return name.isEmpty() ? "Unknown" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    static String extractTitle(String output) {
        Matcher m = TITLE.matcher(output);
        return m.find() ? m.group(1).trim() : "";
    }

    static String extractArtist(String output) {
        Matcher block = ARTIST_ARRAY.matcher(output);
        if (!block.find()) return "";
        List<String> artists = new ArrayList<>();
        Matcher m = ARRAY_STRING.matcher(block.group(1));
        while (m.find()) artists.add(m.group(1).trim());
        return String.join(", ", artists);
    }

    static String extractArtUrl(String output) {
        Matcher m = ART_URL.matcher(output);
        return m.find() ? m.group(1).trim() : "";
    }

    static long extractLength(String output) {
        Matcher m = LENGTH.matcher(output);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    // ---------------------------------------------------------------- Windows / macOS (basic, untested)

    private static MediaMetadata pollWindows() {
        File art = new File(System.getProperty("java.io.tmpdir"), "nowplayingsys_art.jpg");
        String artPath = art.getAbsolutePath().replace("\\", "/");
        String script = "$s = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync().GetAwaiter().GetResult().GetCurrentSession(); "
                + "if ($s) { "
                + "  $p = $s.TryGetMediaPropertiesAsync().GetAwaiter().GetResult(); "
                + "  $artUrl = ''; "
                + "  if ($p.Thumbnail) { "
                + "    $stream = $p.Thumbnail.OpenReadAsync().GetAwaiter().GetResult(); "
                + "    $buffer = New-Object Byte[] $stream.Size; "
                + "    $reader = New-Object Windows.Storage.Streams.DataReader $stream; "
                + "    $reader.LoadAsync($stream.Size).GetAwaiter().GetResult(); "
                + "    $reader.ReadBytes($buffer); "
                + "    [System.IO.File]::WriteAllBytes('" + artPath + "', $buffer); "
                + "    $artUrl = 'file:///" + artPath + "'; "
                + "  }; "
                + "  Write-Output ($p.Title + '|||' + $p.Artist + '|||' + $artUrl) "
                + "}";

        String output = run(List.of("powershell", "-NoProfile", "-Command", script));
        if (output == null || output.isBlank()) return MediaMetadata.NONE;

        String[] parts = output.trim().split("\\|\\|\\|", -1);
        String title = parts[0].trim();
        if (title.isEmpty()) return MediaMetadata.NONE;
        String artist = parts.length > 1 ? parts[1].trim() : "";
        String artUrl = parts.length > 2 ? parts[2].trim() : "";
        return new MediaMetadata("Windows", title, artist, artUrl, PlaybackState.UNKNOWN, -1, -1, System.nanoTime());
    }

    private static MediaMetadata pollMac() {
        String track = run(List.of("osascript", "-e",
                "tell application \"Spotify\" to if running then get name of current track"));
        if (track == null) return MediaMetadata.NONE;
        track = track.trim();
        if (track.isEmpty() || track.equals("missing value")) return MediaMetadata.NONE;
        return new MediaMetadata("Spotify", track, "", "", PlaybackState.UNKNOWN, -1, -1, System.nanoTime());
    }

    // ---------------------------------------------------------------- process helper

    /**
     * Runs a command and returns its stdout, or {@code null} on failure, non-zero exit or timeout.
     * Output is drained concurrently so a chatty process can't block on a full pipe.
     */
    private static String run(List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            if (!warnedMissingDbus) {
                warnedMissingDbus = true;
                NowPlayingSys.LOGGER.warn("Could not run '{}': {}", command.get(0), e.getMessage());
            }
            return null;
        }

        Future<String> output = IO.submit(
                () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        try {
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String text = output.get(1, TimeUnit.SECONDS);
            return process.exitValue() == 0 ? text : null;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            return null;
        }
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}