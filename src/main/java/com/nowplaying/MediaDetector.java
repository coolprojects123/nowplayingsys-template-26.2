package com.nowplaying;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the currently playing track. On Linux (and Minecraft running under Wine) this talks MPRIS
 * through {@code dbus-send}. On Windows it talks to the system media transport controls (the same source as
 * the volume flyout) through one long-lived PowerShell helper. macOS has a basic Spotify-only fallback.
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
    private static volatile boolean windowsSession = false;
    private static volatile String lastWindowsError = "";

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
        return activeMprisPlayer != null || windowsSession;
    }

    /** Sends Previous / Next / PlayPause to the active player without blocking, then re-polls. */
    public static void sendCommand(String method) {
        if (!ALLOWED_COMMANDS.contains(method)) return;
        if (isWindowsNative()) {
            if (!windowsSession) return;
            IO.execute(() -> {
                WindowsHelper.request(method);
                pollSafely();
            });
            return;
        }
        String player = activeMprisPlayer;
        if (player == null) return;
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

    private static boolean isWindowsNative() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") && !isRunningUnderWine();
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

    // ---------------------------------------------------------------- Windows (SMTC via a PowerShell helper)

    private static MediaMetadata pollWindows() {
        String line = WindowsHelper.request("poll");
        if (line == null) {
            windowsSession = false;
            return MediaMetadata.NONE;
        }
        if (line.startsWith("ERR")) {
            String message = line.length() > 4 ? line.substring(4) : "unknown error";
            if (!message.equals(lastWindowsError)) {
                lastWindowsError = message;
                NowPlayingSys.LOGGER.warn("Windows media helper error: {}", message);
            }
            windowsSession = false;
            return MediaMetadata.NONE;
        }
        MediaMetadata metadata = parseWindowsLine(line);
        windowsSession = metadata.hasTrack();
        return metadata;
    }

    /** Parses "OK\tsource\ttitle\tartist\tstatus\tpositionMs\tlengthMs\tartUri" from the helper. */
    static MediaMetadata parseWindowsLine(String line) {
        if (line == null || !line.startsWith("OK\t")) return MediaMetadata.NONE;
        String[] f = line.split("\t", -1);
        if (f.length < 8 || f[2].isBlank()) return MediaMetadata.NONE;

        PlaybackState state = switch (f[4]) {
            case "Playing" -> PlaybackState.PLAYING;
            case "Paused" -> PlaybackState.PAUSED;
            case "Stopped", "Closed" -> PlaybackState.STOPPED;
            default -> PlaybackState.UNKNOWN;
        };
        long positionMs = parseLong(f[5]);
        long lengthMs = parseLong(f[6]);
        return new MediaMetadata(windowsSourceName(f[1]), f[2].trim(), f[3].trim(), f[7].trim(), state,
                positionMs < 0 ? -1 : positionMs * 1_000L, lengthMs <= 0 ? -1 : lengthMs * 1_000L,
                System.nanoTime());
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify" -> "Spotify", "chrome.exe" -> "Chrome". */
    static String windowsSourceName(String appUserModelId) {
        String name = appUserModelId == null ? "" : appUserModelId.trim();
        int bang = name.lastIndexOf('!');
        if (bang >= 0) name = name.substring(bang + 1);
        if (name.toLowerCase(Locale.ROOT).endsWith(".exe")) name = name.substring(0, name.length() - 4);
        int underscore = name.indexOf('_');
        if (underscore > 0) name = name.substring(0, underscore);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) name = name.substring(dot + 1);
        return name.isEmpty() ? "Windows" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * One long-lived powershell.exe (Windows PowerShell 5.1, which can project WinRT types) that answers
     * one-line commands on stdin with one line on stdout. Starting PowerShell and loading WinRT is slow, so
     * it is started once and kept alive; it exits by itself when the game's end of stdin closes.
     */
    private static final class WindowsHelper {
        private record Session(Process process, BufferedWriter in, BlockingQueue<String> lines) {
        }

        private static final long RETRY_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(15);
        private static volatile Session session;
        private static boolean warmedUp;
        private static boolean hookRegistered;
        private static long retryAfterNanos;
        private static boolean backoff;

        static synchronized String request(String command) {
            if (backoff && System.nanoTime() - retryAfterNanos < 0) return null;
            try {
                Session s = session;
                if (s == null || !s.process().isAlive()) {
                    s = start();
                    session = s;
                    warmedUp = false;
                }
                s.lines().clear();
                s.in().write(command);
                s.in().write('\n');
                s.in().flush();

                String line = s.lines().poll(warmedUp ? 6 : 20, TimeUnit.SECONDS);
                if (line == null) {
                    NowPlayingSys.LOGGER.warn("Windows media helper did not answer '{}'; restarting it later", command);
                    fail();
                    return null;
                }
                warmedUp = true;
                backoff = false;
                return line;
            } catch (IOException e) {
                NowPlayingSys.LOGGER.warn("Windows media helper failed ({}). See nowplayingsys_media.log in the temp folder.",
                        e.getMessage());
                fail();
                return null;
            } catch (InterruptedException e) {
                fail();
                Thread.currentThread().interrupt();
                return null;
            }
        }

        private static void fail() {
            Session s = session;
            session = null;
            if (s != null) s.process().destroyForcibly();
            backoff = true;
            retryAfterNanos = System.nanoTime() + RETRY_BACKOFF_NANOS;
        }

        private static Session start() throws IOException {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
            Path script = tmp.resolve("nowplayingsys_media.ps1");
            Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);

            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString())
                    .redirectError(ProcessBuilder.Redirect.to(tmp.resolve("nowplayingsys_media.log").toFile()))
                    .start();

            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) lines.add(l);
                } catch (IOException ignored) {
                    // Process ended.
                }
            }, "MediaDetector-WinReader");
            reader.setDaemon(true);
            reader.start();

            if (!hookRegistered) {
                hookRegistered = true;
                // Lock-free on purpose: must not wait on a request that is still in flight.
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    Session s = session;
                    if (s != null) s.process().destroyForcibly();
                }));
            }
            return new Session(process,
                    new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)),
                    lines);
        }

        // ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less script files as ANSI.
        private static final String SCRIPT = """
                $ErrorActionPreference = 'Stop'
                [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
                Add-Type -AssemblyName System.Runtime.WindowsRuntime
                $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
                $null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime]
                $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
                function Await($op, $type) {
                    $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
                    $null = $task.Wait(-1)
                    return $task.Result
                }
                $mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
                $propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
                $streamType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType]
                $script:mgr = $null
                $script:seq = 0
                $script:lastKey = ''
                $script:lastArt = ''
                $tmp = [System.IO.Path]::GetTempPath()
                function Clean($t) {
                    if ($null -eq $t) { return '' }
                    return $t.ToString().Replace("`t", ' ').Replace("`r", ' ').Replace("`n", ' ')
                }
                function Get-Session {
                    if ($null -eq $script:mgr) { $script:mgr = Await ($mgrType::RequestAsync()) $mgrType }
                    foreach ($s in $script:mgr.GetSessions()) {
                        if ($s.GetPlaybackInfo().PlaybackStatus -eq 'Playing') { return $s }
                    }
                    return $script:mgr.GetCurrentSession()
                }
                function Save-Art($props, $key) {
                    if ($null -eq $props.Thumbnail) { return '' }
                    if ($key -eq $script:lastKey) { return $script:lastArt }
                    $script:lastKey = $key
                    $script:lastArt = ''
                    try {
                        $stream = Await ($props.Thumbnail.OpenReadAsync()) $streamType
                        $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
                        $script:seq++
                        $file = Join-Path $tmp ('nowplayingsys_art_' + $PID + '_' + $script:seq + '.img')
                        $fs = [System.IO.File]::Create($file)
                        try { $net.CopyTo($fs) } finally { $fs.Dispose(); $net.Dispose(); $stream.Dispose() }
                        $old = Join-Path $tmp ('nowplayingsys_art_' + $PID + '_' + ($script:seq - 2) + '.img')
                        if (Test-Path $old) { Remove-Item $old -ErrorAction SilentlyContinue }
                        $script:lastArt = ([System.Uri]$file).AbsoluteUri
                    } catch { $script:lastArt = '' }
                    return $script:lastArt
                }
                function Poll {
                    $s = Get-Session
                    if ($null -eq $s) { return 'NONE' }
                    $props = Await ($s.TryGetMediaPropertiesAsync()) $propsType
                    $title = Clean $props.Title
                    if ($title -eq '') { return 'NONE' }
                    $artist = Clean $props.Artist
                    $status = [string]$s.GetPlaybackInfo().PlaybackStatus
                    $tl = $s.GetTimelineProperties()
                    $len = [long][math]::Round(($tl.EndTime - $tl.StartTime).TotalMilliseconds)
                    $pos = [long][math]::Round($tl.Position.TotalMilliseconds)
                    if ($status -eq 'Playing' -and $tl.LastUpdatedTime.Year -gt 2000) {
                        $pos += [long][math]::Round(([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds)
                    }
                    if ($len -gt 0) { $pos = [long][math]::Max(0L, [math]::Min($pos, $len)) } else { $pos = -1; $len = -1 }
                    $art = Save-Art $props ($title + '|' + $artist + '|' + (Clean $props.AlbumTitle))
                    $src = Clean $s.SourceAppUserModelId
                    return ('OK' + "`t" + $src + "`t" + $title + "`t" + $artist + "`t" + $status + "`t" + $pos + "`t" + $len + "`t" + $art)
                }
                while ($true) {
                    $line = [Console]::In.ReadLine()
                    if ($null -eq $line) { break }
                    $cmd = $line.Trim()
                    try {
                        if ($cmd -eq 'poll') {
                            $r = Poll
                        } elseif ($cmd -eq 'PlayPause' -or $cmd -eq 'Next' -or $cmd -eq 'Previous') {
                            $s = Get-Session
                            if ($null -ne $s) {
                                if ($cmd -eq 'PlayPause') { $null = Await ($s.TryTogglePlayPauseAsync()) ([bool]) }
                                elseif ($cmd -eq 'Next') { $null = Await ($s.TrySkipNextAsync()) ([bool]) }
                                else { $null = Await ($s.TrySkipPreviousAsync()) ([bool]) }
                            }
                            $r = 'ACK'
                        } else {
                            $r = 'ERR' + "`t" + 'unknown command'
                        }
                    } catch {
                        $r = 'ERR' + "`t" + (Clean $_.Exception.Message)
                    }
                    [Console]::Out.WriteLine($r)
                    [Console]::Out.Flush()
                }
                """;
    }

    // ---------------------------------------------------------------- macOS (basic, untested)

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