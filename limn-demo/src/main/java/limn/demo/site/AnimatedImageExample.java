package limn.demo.site;

import limn.animation.ImageAnimation;
import limn.components.ImageView;
import limn.graphics.Image;
import limn.graphics.ImageFormat;
import limn.graphics.Images;

import java.nio.file.Path;

/**
 * The worked example the media guide shows for pictures that move: an animated file played by an
 * image view, a sheet of sprites cut into frames, and an animation written to disk. Compiled by
 * {@code ./gradlew check} and run by its test, so the sample a reader copies is one that builds
 * and does what the guide says.
 */
public final class AnimatedImageExample {

    private AnimatedImageExample() {
    }

    // #region guide:animated-images
    /** An animated file, played by the view it is given to. */
    static ImageView spinner(String resource) {
        ImageView spinner = new ImageView(Images.fromResource(resource));
        spinner.setSpeed(1.5f);                       // half again as fast as the file says
        spinner.setLoop(ImageAnimation.Loop.FOREVER); // whatever the file says
        return spinner;
    }

    /** Holds the frame shown, and plays on from it. */
    static void pause(ImageView view, boolean paused) {
        view.setPlaying(!paused);
    }

    /** A sheet of sprites: eight frames from one row, each shown for 90 ms. */
    static Image coin(Image sheet) {
        return Images.fromSheet(sheet, 8, 1, 90);
    }

    /** Every frame without loss, as an animated PNG; ImageFormat.GIF writes 256 colours. */
    static void save(Image animation, Path file) {
        Images.save(animation, ImageFormat.PNG, file);
    }
    // #endregion
}
