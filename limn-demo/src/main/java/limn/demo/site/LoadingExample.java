package limn.demo.site;

import limn.sound.AudioClip;
import limn.sound.Sounds;
import limn.components.ImageView;
import limn.components.Label;
import limn.graphics.Images;

import java.nio.file.Path;

/**
 * The worked example the media and background-work guides show for loading a file once a window
 * is up (decision 116, DOC-1): the guides used to name {@code Images.loadAsync} and
 * {@code Sounds.loadAsync}, which never existed. Compiled by {@code ./gradlew check}, so the sample
 * a reader copies is one that builds.
 */
public final class LoadingExample {

    private LoadingExample() {
    }

    // #region guide:loading
    /** A picture from disk: read and decoded on the worker pool, set on the UI thread. */
    static void showPicture(ImageView view, Label status, Path file) {
        Images.loadShared(file)                       // cached: every caller shares one Image
                .thenAccept(view::setImage)           // runs on the UI thread
                .exceptionally(error -> {
                    status.setText("Could not open " + file.getFileName());
                    return null;
                });
    }

    /** Bytes already in memory (a download, a database blob): a job you start, and may cancel. */
    static void showDownloaded(ImageView view, byte[] png) {
        Images.decodeAsync(png)
                .onSuccess(view::setImage)
                .start();
    }

    /** A sound bundled with the application. */
    static void preloadClick(java.util.function.Consumer<AudioClip> ready) {
        Sounds.fromResourceShared("sounds/click.wav").thenAccept(ready);
    }
    // #endregion
}
