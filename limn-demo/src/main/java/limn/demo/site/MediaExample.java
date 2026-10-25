package limn.demo.site;

import limn.components.Label;
import limn.components.VideoView;
import limn.components.Viewport3D;
import limn.concurrent.Job;
import limn.concurrent.Ui;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.Light;
import limn.render3d.OrbitController;
import limn.render3d.gltf.GltfLoader;
import limn.render3d.gltf.GltfModel;
import limn.render3d.scene.LightNode;
import limn.render3d.scene.Scene3D;
import limn.sound.AudioBus;
import limn.sound.PlayOptions;
import limn.video.MediaPlayer;
import limn.video.Videos;
import limn.video.ffmpeg.FfmpegMedia;
import limn.video.ffmpeg.FfmpegVideoDecoder;
import limn.video.ffmpeg.SubtitleCues;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The worked examples the media guide shows for playing a film and showing a glTF model: where a
 * {@link VideoView}'s stream comes from, the player that gives it a soundtrack, and the renderer,
 * camera and lights a {@link Viewport3D} draws a loaded model with. The guide used to show the
 * widgets fed by a {@code source} and a {@code renderer} it never said how to make. Compiled by
 * {@code ./gradlew check}, so the sample a reader copies is one that builds.
 */
public final class MediaExample {

    private MediaExample() {
    }

    // #region guide:video-open
    /** Once, where the application starts: the decoder that reads MP4 and Matroska. */
    static void installDecoders() {
        Videos.installDecoder(new FfmpegVideoDecoder());
        Videos.warmUpAsync().start();                    // links the native library off the UI thread
    }

    /** The picture alone: a clip with no sound, or one whose sound the screen does not want. */
    static Job showClip(VideoView view, Label status, Path file) {
        return Videos.openAsync(file)                    // opened on the worker pool
                .onSuccess(stream -> view.setSource(stream).setPaused(false))
                .onFailure(error -> status.setText(error.getMessage()))
                .start();
    }
    // #endregion

    // #region guide:video-player
    /** A film and its soundtrack; closed by the screen once it no longer shows them. */
    record Film(FfmpegMedia media, MediaPlayer player) implements AutoCloseable {
        @Override
        public void close() {
            player.close();                              // stops reading first,
            media.close();                               // then frees the decoder
        }
    }

    /** Opens a film with its sound, shows it with the built-in controls, and starts it. */
    static Job playFilm(VideoView view, Path file, Consumer<Film> opened) {
        return Ui.work(progress -> FfmpegMedia.open(file))   // a header, a probe, a decoder: slow
                .onSuccess(media -> {
                    MediaPlayer player = new MediaPlayer(media.video());
                    if (media.hasAudio()) {
                        player.setAudio(media.audio(), PlayOptions.DEFAULTS.withBus(AudioBus.MUSIC));
                    }
                    view.setPlayer(player).setControlsVisible(true);
                    player.start();                      // a view never starts a player itself
                    opened.accept(new Film(media, player));
                })
                .onDiscarded(FfmpegMedia::close)         // withdrawn before it arrived
                .start();
    }

    /** The subtitle lines to draw over the picture now, once a track is chosen. */
    static List<String> subtitleLines(Film film) {
        List<String> lines = new ArrayList<>();
        for (SubtitleCues.Cue cue : film.media().subtitles().activeAt(film.player().positionMicros())) {
            lines.add(cue.text());
        }
        return lines;
    }
    // #endregion

    // #region guide:gltf
    /** A glTF model from disk: read and decoded on the worker pool, uploaded by the first frame after. */
    static Viewport3D modelViewer(Path file) {
        Viewport3D viewport = new Viewport3D().setPreferredSize(640, 420);
        viewport.setAnimated(false);                     // a still model: redraw on orbit and zoom only
        viewport.setController(new OrbitController(viewport.camera()));   // drag orbits, wheel zooms
        GltfModel[] model = {null};
        GltfModel.DecodedTextures[] textures = {null};
        Scene3D[] shown = {null};

        viewport.setRenderer((pass, seconds) -> {
            if (shown[0] == null && textures[0] != null) {
                Scene3D scene = model[0].toScene3D(textures[0])   // the upload: it needs the frame
                        .background(new Vec4(0.08f, 0.08f, 0.1f, 1f))
                        .exposure(1.1f);
                scene.root().add(new LightNode(Light.Directional.of(
                        new Vec3(0.4f, 0.9f, 0.5f), new Vec3(1f, 0.96f, 0.9f))));
                shown[0] = scene;
                Ui.post(() -> viewport.frameContent(scene.bounds()));   // the camera moves after this frame
            }
            if (shown[0] != null) {
                float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
                shown[0].render(pass, viewport.camera(), aspect);
            }
        });

        GltfLoader.loadAsync(file)
                .onSuccess(loaded -> loaded.decodeTexturesAsync()
                        .onSuccess(decoded -> {
                            model[0] = loaded;
                            textures[0] = decoded;
                            viewport.invalidate();       // the next frame uploads and draws it
                        })
                        .start())
                .start();

        viewport.observeDispose(() -> {                  // taken out of its window: free the GPU copy
            if (shown[0] != null) {
                shown[0].dispose();
                shown[0] = null;
            }
        });
        return viewport;
    }
    // #endregion
}
