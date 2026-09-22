package limn.backend;

import java.util.Objects;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.FontCatalog;
import limn.graphics.FontLoader;
import limn.graphics.Fonts;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.graphics.SvgIcon;
import limn.graphics.SvgRasterizer;
import limn.graphics.TextRuler;
import limn.graphics.TextRulers;
import limn.render3d.Graphics3D;
import limn.sound.AudioDecoder;
import limn.sound.AudioEngine;
import limn.sound.Sounds;
import limn.video.VideoSurfaces;

/**
 * Everything a {@link Backend} provides to the toolkit besides its windows, named in one place and
 * installed by one call (ADR 046 §5).
 *
 * <p>Until 2026-09-22 a backend installed each of these through a static setter of its own, and
 * nothing on the SPI said which it owed: a backend that forgot the text ruler measured every string
 * as zero wide, with no error anywhere. A backend now builds this record in its constructor and calls
 * {@link #install()}; the record's constructor refuses a missing required service on the spot.
 *
 * <p>Five services are <b>required</b> — nothing draws without them. Four are <b>optional</b>, and
 * {@code null} is how a backend says it has none: a machine with no audio device, a backend without
 * 3D or without GPU video surfaces. The toolkit then reports the capability as unavailable
 * ({@code Sounds.isAvailable()}, {@code Graphics3D}, {@code VideoSurfaces}) rather than failing.
 *
 * @param ui            the user-interface thread's runtime, bound to the thread that will run the loop
 * @param textRuler     measures and shapes text
 * @param fontCatalog   the font families the platform offers
 * @param fontLoader    reads a font file the application names
 * @param imageDecoder  decodes image files
 * @param svgRasterizer rasterizes SVG icons
 * @param graphics3d    the 3D viewport's renderer, or {@code null} for none
 * @param videoSurfaces GPU surfaces a video view composites, or {@code null} for none
 * @param audioEngine   plays sound, or {@code null} for no audio device
 * @param audioDecoder  decodes sound files, or {@code null} when there is no audio at all
 */
public record BackendServices(UiRuntime ui, TextRuler textRuler, FontCatalog fontCatalog,
                              FontLoader fontLoader, ImageDecoder imageDecoder,
                              SvgRasterizer svgRasterizer, Graphics3D.Provider graphics3d,
                              VideoSurfaces.Provider videoSurfaces, AudioEngine audioEngine,
                              AudioDecoder audioDecoder) {

    public BackendServices {
        Objects.requireNonNull(ui, "ui: a backend must provide the user-interface runtime");
        Objects.requireNonNull(textRuler, "textRuler: without it every text measures zero");
        Objects.requireNonNull(fontCatalog, "fontCatalog (FontCatalog.EMPTY when the platform lists none)");
        Objects.requireNonNull(fontLoader, "fontLoader (FontLoader.UNAVAILABLE when files cannot be read)");
        Objects.requireNonNull(imageDecoder, "imageDecoder: images and icons decode through it");
        Objects.requireNonNull(svgRasterizer, "svgRasterizer: every icon is an SVG");
    }

    /**
     * Installs every service where the toolkit reads it. Once, from the backend's constructor, on the
     * thread the backend's loop will run on.
     */
    public void install() {
        Ui.install(ui);
        TextRulers.install(textRuler);
        Fonts.installCatalog(fontCatalog);
        Fonts.installLoader(fontLoader);
        Images.installDecoder(imageDecoder);
        SvgIcon.installRasterizer(svgRasterizer);
        if (graphics3d != null) {
            Graphics3D.install(graphics3d);
        }
        if (videoSurfaces != null) {
            VideoSurfaces.install(videoSurfaces);
        }
        if (audioEngine != null) {
            Sounds.installEngine(audioEngine);
        }
        if (audioDecoder != null) {
            Sounds.installDecoder(audioDecoder);
        }
    }
}
