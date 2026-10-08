package com.nowplaying.gui;

import com.daqem.uilib.gui.AbstractScreen;
import com.daqem.uilib.gui.background.DarkenedBackground;
import com.daqem.uilib.gui.component.AbstractComponent;
import com.daqem.uilib.gui.component.color.ColorComponent;
import com.daqem.uilib.gui.component.text.TextAlign;
import com.daqem.uilib.gui.component.text.TruncatedTextComponent;
import com.daqem.uilib.gui.widget.ButtonWidget;
import com.mojang.blaze3d.platform.NativeImage;
import com.nowplaying.MediaDetector;
import com.nowplaying.MediaDetector.MediaMetadata;
import com.nowplaying.NowPlayingSys;
import com.nowplaying.MediaDetector;
import com.nowplaying.MediaDetector.MediaMetadata;
import com.nowplaying.NowPlayingSys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URI;
import java.net.URLConnection;
import java.util.Locale;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NowPlayingScreen extends AbstractScreen {
    private static final int PANEL_WIDTH = 392;
    private static final int PANEL_HEIGHT = 228;
    private static final int PANEL_INSET = 8;
    private static final int PANEL_INSET = 8;
    private static final int ALBUM_SIZE = 144;
    private static final int PROGRESS_WIDTH = 182;
    private static final int PROGRESS_HEIGHT = 6;
    /** Right column is 192 wide and text starts 8 in; leave a margin on the right too (and 1px for the title shadow). */
    private static final int TEXT_MAX_WIDTH = 168;

    private static final Identifier FALLBACK_ALBUM_ART =
            Identifier.fromNamespaceAndPath(NowPlayingSys.MOD_ID, "textures/gui/album_art.png");
    /** Right column is 192 wide and text starts 8 in; leave a margin on the right too (and 1px for the title shadow). */
    private static final int TEXT_MAX_WIDTH = 168;

    private static final Identifier FALLBACK_ALBUM_ART =
            Identifier.fromNamespaceAndPath(NowPlayingSys.MOD_ID, "textures/gui/album_art.png");
    private static final ExecutorService ART_LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NowPlaying-AlbumArtLoader");
        thread.setDaemon(true);
        return thread;
    });

    private TruncatedTextComponent titleText;
    private TruncatedTextComponent artistText;
    private TruncatedTextComponent sourceText;
    private TruncatedTextComponent statusText;
    private AlbumArtComponent albumArtComponent;
    private ColorComponent progressFill;
    private ButtonWidget prevButton;
    private ButtonWidget playPauseButton;
    private ButtonWidget nextButton;

    /** Last snapshot applied to the widgets; compared by identity so we only touch the UI when it changes. */
    private MediaMetadata shown;
    private int shownFillWidth = -1;

    // Album art state. Only one load runs at a time and the latest wanted URL wins, so superseded
    // results are closed instead of leaking, and a failing URL is not retried every poll.
    private String wantedArtUrl = "";

    /** Last snapshot applied to the widgets; compared by identity so we only touch the UI when it changes. */
    private MediaMetadata shown;
    private int shownFillWidth = -1;

    // Album art state. Only one load runs at a time and the latest wanted URL wins, so superseded
    // results are closed instead of leaking, and a failing URL is not retried every poll.
    private String wantedArtUrl = "";
    private String appliedArtUrl = "";
    private String failedArtUrl = "";
    private String inFlightArtUrl = "";
    private String failedArtUrl = "";
    private String inFlightArtUrl = "";
    private CompletableFuture<AlbumArtPayload> albumArtFuture;
    private Identifier dynamicArtTextureId;

    private record AlbumArtPayload(String sourceUrl, NativeImage image, int width, int height) {
    }

    public NowPlayingScreen() {
        super(Component.literal("Now Playing"));
        this.setBackground(new DarkenedBackground());
    }

    @Override
    public void init() {
        this.clear();
        MediaDetector.start();
        MediaDetector.start();

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;
        int rightPaneX = panelX + 184;

        ColorComponent panel = new ColorComponent(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 0xFF131A27);
        ColorComponent panelInner = new ColorComponent(panelX + PANEL_INSET, panelY + PANEL_INSET,
                PANEL_WIDTH - PANEL_INSET * 2, PANEL_HEIGHT - PANEL_INSET * 2, 0xFF1C2C43);
        ColorComponent panelInner = new ColorComponent(panelX + PANEL_INSET, panelY + PANEL_INSET,
                PANEL_WIDTH - PANEL_INSET * 2, PANEL_HEIGHT - PANEL_INSET * 2, 0xFF1C2C43);
        ColorComponent leftColumn = new ColorComponent(panelX + 16, panelY + 16, 160, PANEL_HEIGHT - 32, 0xCC101B2A);
        ColorComponent rightColumn = new ColorComponent(panelX + 184, panelY + 16, 192, PANEL_HEIGHT - 32, 0xCC0F1826);
        ColorComponent divider = new ColorComponent(panelX + 180, panelY + 20, 1, PANEL_HEIGHT - 40, 0xFF2F4361);
        ColorComponent artFrame = new ColorComponent(panelX + 24, panelY + 24, ALBUM_SIZE, ALBUM_SIZE, 0xFF2A3F5E);

        // init() also runs on window resize; keep showing the loaded art instead of dropping to the fallback.
        albumArtComponent = new AlbumArtComponent(panelX + 24, panelY + 24, ALBUM_SIZE, ALBUM_SIZE,
                dynamicArtTextureId != null ? dynamicArtTextureId : FALLBACK_ALBUM_ART, ALBUM_SIZE, ALBUM_SIZE);

        // init() also runs on window resize; keep showing the loaded art instead of dropping to the fallback.
        albumArtComponent = new AlbumArtComponent(panelX + 24, panelY + 24, ALBUM_SIZE, ALBUM_SIZE,
                dynamicArtTextureId != null ? dynamicArtTextureId : FALLBACK_ALBUM_ART, ALBUM_SIZE, ALBUM_SIZE);

        titleText = new TruncatedTextComponent(rightPaneX + 8, panelY + 28, 176, Component.literal(""), 0xFFF1F7FF);
        titleText = new TruncatedTextComponent(rightPaneX + 8, panelY + 28, 176, Component.literal(""), 0xFFF1F7FF);
        titleText.setDrawShadow(true);
        titleText.setTextAlign(TextAlign.LEFT);

        artistText = new TruncatedTextComponent(rightPaneX + 8, panelY + 48, 176, Component.literal(""), 0xFFB6C3D6);
        artistText = new TruncatedTextComponent(rightPaneX + 8, panelY + 48, 176, Component.literal(""), 0xFFB6C3D6);
        artistText.setTextAlign(TextAlign.LEFT);

        sourceText = new TruncatedTextComponent(rightPaneX + 8, panelY + 72, 176, Component.literal(""), 0xFF8FA8C8);
        sourceText = new TruncatedTextComponent(rightPaneX + 8, panelY + 72, 176, Component.literal(""), 0xFF8FA8C8);
        sourceText.setTextAlign(TextAlign.LEFT);

        statusText = new TruncatedTextComponent(rightPaneX + 8, panelY + 92, 176, Component.literal(""), 0xFF93A1B5);
        statusText = new TruncatedTextComponent(rightPaneX + 8, panelY + 92, 176, Component.literal(""), 0xFF93A1B5);
        statusText.setTextAlign(TextAlign.LEFT);

        ColorComponent progressTrack = new ColorComponent(rightPaneX + 8, panelY + 122, PROGRESS_WIDTH, PROGRESS_HEIGHT, 0xFF1A3557);
        ColorComponent progressTrack = new ColorComponent(rightPaneX + 8, panelY + 122, PROGRESS_WIDTH, PROGRESS_HEIGHT, 0xFF1A3557);
        progressFill = new ColorComponent(rightPaneX + 8, panelY + 122, 0, PROGRESS_HEIGHT, 0xFF57A6FF);

        String labelText = "Now Playing";
        int labelWidth = Minecraft.getInstance().font.width(labelText);
        TruncatedTextComponent nowPlayingLabel = new TruncatedTextComponent(
                panelX + 24 + (ALBUM_SIZE - labelWidth) / 2, panelY + 176, labelWidth + 4,
                Component.literal(labelText), 0xFFD9E6F7);
        nowPlayingLabel.setTextAlign(TextAlign.LEFT);
        String labelText = "Now Playing";
        int labelWidth = Minecraft.getInstance().font.width(labelText);
        TruncatedTextComponent nowPlayingLabel = new TruncatedTextComponent(
                panelX + 24 + (ALBUM_SIZE - labelWidth) / 2, panelY + 176, labelWidth + 4,
                Component.literal(labelText), 0xFFD9E6F7);
        nowPlayingLabel.setTextAlign(TextAlign.LEFT);

        prevButton = new ButtonWidget(rightPaneX + 8, panelY + 156, 56, 20, Component.literal("Prev"),
                button -> MediaDetector.sendCommand("Previous"));
        prevButton = new ButtonWidget(rightPaneX + 8, panelY + 156, 56, 20, Component.literal("Prev"),
                button -> MediaDetector.sendCommand("Previous"));
        prevButton.setTooltip(Tooltip.create(Component.literal("Previous Track")));

        playPauseButton = new ButtonWidget(rightPaneX + 70, panelY + 156, 56, 20, Component.literal("Play"),
                button -> MediaDetector.sendCommand("PlayPause"));
        playPauseButton = new ButtonWidget(rightPaneX + 70, panelY + 156, 56, 20, Component.literal("Play"),
                button -> MediaDetector.sendCommand("PlayPause"));
        playPauseButton.setTooltip(Tooltip.create(Component.literal("Play / Pause")));

        nextButton = new ButtonWidget(rightPaneX + 132, panelY + 156, 56, 20, Component.literal("Next"),
                button -> MediaDetector.sendCommand("Next"));
        nextButton = new ButtonWidget(rightPaneX + 132, panelY + 156, 56, 20, Component.literal("Next"),
                button -> MediaDetector.sendCommand("Next"));
        nextButton.setTooltip(Tooltip.create(Component.literal("Next Track")));

        ButtonWidget refreshButton = new ButtonWidget(rightPaneX + 8, panelY + 182, 180, 20,
                Component.literal("Refresh Metadata"), button -> MediaDetector.refreshAsync());
        ButtonWidget refreshButton = new ButtonWidget(rightPaneX + 8, panelY + 182, 180, 20,
                Component.literal("Refresh Metadata"), button -> MediaDetector.refreshAsync());
        refreshButton.setTooltip(Tooltip.create(Component.literal("Force a fresh metadata + album art fetch")));

        this.addComponent(panel);
        this.addComponent(panelInner);
        this.addComponent(panelInner);
        this.addComponent(leftColumn);
        this.addComponent(rightColumn);
        this.addComponent(divider);
        this.addComponent(artFrame);
        this.addComponent(albumArtComponent);
        this.addComponent(nowPlayingLabel);
        this.addComponent(nowPlayingLabel);
        this.addComponent(titleText);
        this.addComponent(artistText);
        this.addComponent(sourceText);
        this.addComponent(statusText);
        this.addComponent(progressTrack);
        this.addComponent(progressFill);

        this.addWidget(prevButton);
        this.addWidget(playPauseButton);
        this.addWidget(nextButton);
        this.addWidget(refreshButton);

        // Widgets were just rebuilt, so force a full re-apply of the current snapshot.
        shown = null;
        shownFillWidth = -1;
        refreshFromSnapshot();

        // Widgets were just rebuilt, so force a full re-apply of the current snapshot.
        shown = null;
        shownFillWidth = -1;
        refreshFromSnapshot();

        super.init();
    }

    @Override
    public void tick() {
        super.tick();
        refreshFromSnapshot();
        pollAlbumArt();
    }

    /** Applies text/controls only when the snapshot changed; progress is extrapolated every tick. */
    private void refreshFromSnapshot() {
        MediaMetadata snapshot = MediaDetector.getCurrentlyPlayingMetadata();
        if (snapshot != shown) {
            shown = snapshot;
            applyMetadata(snapshot);
        }
        updateProgress(snapshot);
        refreshFromSnapshot();
        pollAlbumArt();
    }

    /** Applies text/controls only when the snapshot changed; progress is extrapolated every tick. */
    private void refreshFromSnapshot() {
        MediaMetadata snapshot = MediaDetector.getCurrentlyPlayingMetadata();
        if (snapshot != shown) {
            shown = snapshot;
            applyMetadata(snapshot);
        }
        updateProgress(snapshot);
    }

    private void applyMetadata(MediaMetadata metadata) {
        if (!metadata.hasTrack()) {
            titleText.setText(Component.literal(fit("No media detected")));
            artistText.setText(Component.literal(fit("Open a player to begin")));
    private void applyMetadata(MediaMetadata metadata) {
        if (!metadata.hasTrack()) {
            titleText.setText(Component.literal(fit("No media detected")));
            artistText.setText(Component.literal(fit("Open a player to begin")));
            sourceText.setText(Component.literal("Source: None"));
            statusText.setText(Component.literal("Status: Idle"));
            playPauseButton.setMessage(Component.literal("Play"));
            setControlsEnabled(false);
            wantedArtUrl = "";
            wantedArtUrl = "";
            setFallbackAlbumArt();
            return;
        }

        titleText.setText(Component.literal(fit(metadata.title())));
        String artist = metadata.artist();
        artistText.setText(Component.literal(fit(artist == null || artist.isBlank() ? "Unknown Artist" : artist)));
        sourceText.setText(Component.literal(fit("Source: " + metadata.source())));
        statusText.setText(Component.literal(fit("Status: " + statusLabel(metadata.state()))));
        playPauseButton.setMessage(Component.literal(metadata.isPlaying() ? "Pause" : "Play"));
        setControlsEnabled(MediaDetector.canControl());
        titleText.setText(Component.literal(fit(metadata.title())));
        String artist = metadata.artist();
        artistText.setText(Component.literal(fit(artist == null || artist.isBlank() ? "Unknown Artist" : artist)));
        sourceText.setText(Component.literal(fit("Source: " + metadata.source())));
        statusText.setText(Component.literal(fit("Status: " + statusLabel(metadata.state()))));
        playPauseButton.setMessage(Component.literal(metadata.isPlaying() ? "Pause" : "Play"));
        setControlsEnabled(MediaDetector.canControl());

        if (metadata.hasArtUrl()) {
            queueAlbumArtLoad(metadata.artUrl());
        } else {
            wantedArtUrl = "";
            wantedArtUrl = "";
            setFallbackAlbumArt();
        }
    }

    /** Truncates with "..." so long titles can't spill out of the panel (TruncatedTextComponent didn't). */
    private static String fit(String text) {
        Font font = Minecraft.getInstance().font;
        if (font.width(text) <= TEXT_MAX_WIDTH) {
            return text;
        }
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, TEXT_MAX_WIDTH - font.width(ellipsis)) + ellipsis;
    }

    private static String statusLabel(MediaDetector.PlaybackState state) {
        return switch (state) {
            case PLAYING -> "Playing";
            case PAUSED -> "Paused";
            case STOPPED -> "Stopped";
            case UNKNOWN -> "Active";
        };
    }

    private void updateProgress(MediaMetadata metadata) {
        float progress = metadata.hasTrack() && metadata.hasTimeline() ? metadata.progress() : 0f;
        int width = Math.clamp(Math.round(PROGRESS_WIDTH * progress), 0, PROGRESS_WIDTH);
        if (width != shownFillWidth) {
            shownFillWidth = width;
            progressFill.setWidth(width);
        }
    }

    // ------------------------------------------------------------------ album art
    /** Truncates with "..." so long titles can't spill out of the panel (TruncatedTextComponent didn't). */
    private static String fit(String text) {
        Font font = Minecraft.getInstance().font;
        if (font.width(text) <= TEXT_MAX_WIDTH) {
            return text;
        }
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, TEXT_MAX_WIDTH - font.width(ellipsis)) + ellipsis;
    }

    private static String statusLabel(MediaDetector.PlaybackState state) {
        return switch (state) {
            case PLAYING -> "Playing";
            case PAUSED -> "Paused";
            case STOPPED -> "Stopped";
            case UNKNOWN -> "Active";
        };
    }

    private void updateProgress(MediaMetadata metadata) {
        float progress = metadata.hasTrack() && metadata.hasTimeline() ? metadata.progress() : 0f;
        int width = Math.clamp(Math.round(PROGRESS_WIDTH * progress), 0, PROGRESS_WIDTH);
        if (width != shownFillWidth) {
            shownFillWidth = width;
            progressFill.setWidth(width);
        }
    }

    // ------------------------------------------------------------------ album art

    private void queueAlbumArtLoad(String artUrl) {
        wantedArtUrl = artUrl;
        if (artUrl.equals(appliedArtUrl) || artUrl.equals(failedArtUrl)) {
        wantedArtUrl = artUrl;
        if (artUrl.equals(appliedArtUrl) || artUrl.equals(failedArtUrl)) {
            return;
        }
        startArtLoadIfIdle();
    }
        }
        startArtLoadIfIdle();
    }

    private void startArtLoadIfIdle() {
        if (albumArtFuture != null || wantedArtUrl.isBlank()) {
    private void startArtLoadIfIdle() {
        if (albumArtFuture != null || wantedArtUrl.isBlank()) {
            return;
        }
        String url = wantedArtUrl;
        inFlightArtUrl = url;
        albumArtFuture = CompletableFuture.supplyAsync(() -> loadAlbumArt(url), ART_LOADER);
    }
        }
        String url = wantedArtUrl;
        inFlightArtUrl = url;
        albumArtFuture = CompletableFuture.supplyAsync(() -> loadAlbumArt(url), ART_LOADER);
    }

    private void pollAlbumArt() {
        if (albumArtFuture == null || !albumArtFuture.isDone()) {
    private void pollAlbumArt() {
        if (albumArtFuture == null || !albumArtFuture.isDone()) {
            return;
        }
        CompletableFuture<AlbumArtPayload> finished = albumArtFuture;
        albumArtFuture = null;
        String url = inFlightArtUrl;

        try {
            AlbumArtPayload payload = finished.join();
            if (payload.sourceUrl().equals(wantedArtUrl)) {
                applyAlbumArt(payload);
            } else {
                payload.image().close(); // superseded while loading
            }
        } catch (CompletionException e) {
            NowPlayingSys.LOGGER.warn("Album art load failed for {}: {}", url, String.valueOf(e.getCause()));
            failedArtUrl = url;
            if (url.equals(wantedArtUrl)) {
                setFallbackAlbumArt();
            }
        }

        // The wanted URL may have changed while this one was loading.
        if (!wantedArtUrl.isBlank() && !wantedArtUrl.equals(appliedArtUrl) && !wantedArtUrl.equals(failedArtUrl)) {
            startArtLoadIfIdle();
        }
    }

    /** Runs on the loader thread. Supports http(s) and local file: URLs (many Linux players use file:). */
    private static AlbumArtPayload loadAlbumArt(String artUrl) {
        URLConnection connection = null;
        CompletableFuture<AlbumArtPayload> finished = albumArtFuture;
        albumArtFuture = null;
        String url = inFlightArtUrl;

        try {
            AlbumArtPayload payload = finished.join();
            if (payload.sourceUrl().equals(wantedArtUrl)) {
                applyAlbumArt(payload);
            } else {
                payload.image().close(); // superseded while loading
            }
        } catch (CompletionException e) {
            NowPlayingSys.LOGGER.warn("Album art load failed for {}: {}", url, String.valueOf(e.getCause()));
            failedArtUrl = url;
            if (url.equals(wantedArtUrl)) {
                setFallbackAlbumArt();
            }
        }

        // The wanted URL may have changed while this one was loading.
        if (!wantedArtUrl.isBlank() && !wantedArtUrl.equals(appliedArtUrl) && !wantedArtUrl.equals(failedArtUrl)) {
            startArtLoadIfIdle();
        }
    }

    /** Runs on the loader thread. Supports http(s) and local file: URLs (many Linux players use file:). */
    private static AlbumArtPayload loadAlbumArt(String artUrl) {
        URLConnection connection = null;
        try {
            URI uri = URI.create(artUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("file")) {
                throw new IOException("Unsupported album art URL scheme: " + scheme);
            }

            connection = uri.toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "NowPlayingSys/1.0");
            URI uri = URI.create(artUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("file")) {
                throw new IOException("Unsupported album art URL scheme: " + scheme);
            }

            connection = uri.toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "NowPlayingSys/1.0");

            try (InputStream in = connection.getInputStream()) {
                BufferedImage original = ImageIO.read(in);
                if (original == null) {
                    throw new IOException("Failed to decode image");
                }
                BufferedImage thumbnail = createSquareThumbnail(original, ALBUM_SIZE);
                return new AlbumArtPayload(artUrl, toNativeImage(thumbnail), ALBUM_SIZE, ALBUM_SIZE);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new CompletionException(e);
        } finally {
            if (connection instanceof HttpURLConnection http) {
                http.disconnect();
            }
        }
    }

    private static NativeImage toNativeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    nativeImage.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            return nativeImage;
        } catch (RuntimeException e) {
            nativeImage.close();
            throw e;
        }
    }

    /** Center-crops to a square (so non-square art isn't stretched), then downscales in steps for quality. */
    private static BufferedImage createSquareThumbnail(BufferedImage source, int size) {
        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage current = source.getSubimage(
                (source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);

        int w = side;
        while (w > size * 2) {
            try (InputStream in = connection.getInputStream()) {
                BufferedImage original = ImageIO.read(in);
                if (original == null) {
                    throw new IOException("Failed to decode image");
                }
                BufferedImage thumbnail = createSquareThumbnail(original, ALBUM_SIZE);
                return new AlbumArtPayload(artUrl, toNativeImage(thumbnail), ALBUM_SIZE, ALBUM_SIZE);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new CompletionException(e);
        } finally {
            if (connection instanceof HttpURLConnection http) {
                http.disconnect();
            }
        }
    }

    private static NativeImage toNativeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    nativeImage.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            return nativeImage;
        } catch (RuntimeException e) {
            nativeImage.close();
            throw e;
        }
    }

    /** Center-crops to a square (so non-square art isn't stretched), then downscales in steps for quality. */
    private static BufferedImage createSquareThumbnail(BufferedImage source, int size) {
        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage current = source.getSubimage(
                (source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);

        int w = side;
        while (w > size * 2) {
            w /= 2;
            BufferedImage scratch = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scratch.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(current, 0, 0, w, w, null);
            g.dispose();
            current = scratch;
            BufferedImage scratch = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scratch.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(current, 0, 0, w, w, null);
            g.dispose();
            current = scratch;
        }

        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(current, 0, 0, size, size, null);
        g.dispose();
        return result;
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(current, 0, 0, size, size, null);
        g.dispose();
        return result;
    }

    private void applyAlbumArt(AlbumArtPayload payload) {
        Minecraft client = Minecraft.getInstance();
        releaseDynamicAlbumArt();

        Identifier textureId = Identifier.fromNamespaceAndPath(NowPlayingSys.MOD_ID,
                "dynamic/album_art_" + Integer.toUnsignedString(payload.sourceUrl().hashCode()));
        client.getTextureManager().register(textureId,
                new DynamicTexture(() -> "NowPlayingAlbumArt", payload.image()));
        Identifier textureId = Identifier.fromNamespaceAndPath(NowPlayingSys.MOD_ID,
                "dynamic/album_art_" + Integer.toUnsignedString(payload.sourceUrl().hashCode()));
        client.getTextureManager().register(textureId,
                new DynamicTexture(() -> "NowPlayingAlbumArt", payload.image()));

        dynamicArtTextureId = textureId;
        appliedArtUrl = payload.sourceUrl();
        albumArtComponent.setTexture(textureId, payload.width(), payload.height());
    }

    private void setFallbackAlbumArt() {
        releaseDynamicAlbumArt();
        appliedArtUrl = "";
        if (albumArtComponent != null) {
            albumArtComponent.setTexture(FALLBACK_ALBUM_ART, ALBUM_SIZE, ALBUM_SIZE);
        }
    }

    /** Releasing through the TextureManager closes the texture (and its image) as well. */
    /** Releasing through the TextureManager closes the texture (and its image) as well. */
    private void releaseDynamicAlbumArt() {
        if (dynamicArtTextureId != null) {
            Minecraft.getInstance().getTextureManager().release(dynamicArtTextureId);
            dynamicArtTextureId = null;
        }
    }

    // ------------------------------------------------------------------ screen plumbing

    private void setControlsEnabled(boolean enabled) {
        prevButton.active = enabled;
        playPauseButton.active = enabled;
        nextButton.active = enabled;
        if (dynamicArtTextureId != null) {
            Minecraft.getInstance().getTextureManager().release(dynamicArtTextureId);
            dynamicArtTextureId = null;
        }
    }

    // ------------------------------------------------------------------ screen plumbing

    private void setControlsEnabled(boolean enabled) {
        prevButton.active = enabled;
        playPauseButton.active = enabled;
        nextButton.active = enabled;
    }

    @Override
    public void removed() {
        MediaDetector.stop();
        // Any in-flight load finishes on its own thread; its result is dropped with the screen.
        MediaDetector.stop();
        // Any in-flight load finishes on its own thread; its result is dropped with the screen.
        albumArtFuture = null;
        releaseDynamicAlbumArt();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static class AlbumArtComponent extends AbstractComponent {
        private Identifier texture;
        private int sourceWidth;
        private int sourceHeight;

        AlbumArtComponent(int x, int y, int width, int height, Identifier texture, int sourceWidth, int sourceHeight) {
            super(x, y, width, height);
            setTexture(texture, sourceWidth, sourceHeight);
            setTexture(texture, sourceWidth, sourceHeight);
        }

        void setTexture(Identifier texture, int sourceWidth, int sourceHeight) {
            this.texture = texture;
            this.sourceWidth = Math.max(1, sourceWidth);
            this.sourceHeight = Math.max(1, sourceHeight);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                       int parentWidth, int parentHeight) {
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                       int parentWidth, int parentHeight) {
            int x = this.getTotalX();
            int y = this.getTotalY();
            int width = this.getWidth();
            int height = this.getHeight();

            graphics.fill(x, y, x + width, y + height, 0xFF121A28);
            graphics.fill(x, y, x + width, y + height, 0xFF121A28);
            if (texture == null) {
                return;
            }

            // Fit inside the component, preserving the source aspect ratio.
            // Fit inside the component, preserving the source aspect ratio.
            float sourceAspect = (float) sourceWidth / (float) sourceHeight;
            float targetAspect = (float) width / (float) height;
            int drawWidth = width;
            int drawHeight = height;
            if (sourceAspect > targetAspect) {
                drawHeight = Math.max(1, Math.round(width / sourceAspect));
            } else {
                drawWidth = Math.max(1, Math.round(height * sourceAspect));
            }
            int drawX = x + (width - drawWidth) / 2;
            int drawY = y + (height - drawHeight) / 2;

            // Full-region overload: draw the whole texture (region == texture size) scaled to drawWidth x drawHeight.
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, drawX, drawY, 0.0f, 0.0f, drawWidth, drawHeight,
                    sourceWidth, sourceHeight, sourceWidth, sourceHeight);
        }
    }
}