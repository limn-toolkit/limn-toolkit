package limn.video;

import limn.graphics.Image;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * What a video shows before it has played: its first picture, the picture at a time, a picture of
 * the application's, or nothing but the bar colour. A poster is a cover, not a starting point:
 * playback begins at the start of the stream whichever one is shown.
 *
 * <pre>{@code
 * view.setPoster(Poster.at(Duration.ofSeconds(3)));   // a frame that says what the clip is
 * view.setPoster(Poster.image(Images.fromResource("/clips/onboarding.png")));
 * }</pre>
 *
 * <p>Made by the four factories and no other way, so each value has one spelling; the kinds are
 * there to be told apart ({@code poster instanceof Poster.At at}) and read, not built.
 *
 * <p>By time rather than by a picture's number: a stream is moved to an instant, and keeps no
 * index of its pictures, so a number would cost decoding every picture before it, and on a stream
 * whose rate varies the same number would land at different instants. At a constant rate, picture
 * <i>n</i> is at <i>n</i> divided by the rate.
 */
public sealed interface Poster permits Poster.FirstPicture, Poster.At, Poster.Still, Poster.None {

    /** The stream's first picture: the default. */
    static Poster firstPicture() {
        return FirstPicture.INSTANCE;
    }

    /**
     * The picture at {@code time}, reached by an exact seek: the first at or after it. A stream that
     * cannot seek shows its first picture instead, and a time past the end shows nothing.
     *
     * @param time where the picture is on the stream's own timeline; not negative
     * @return the poster
     * @throws IllegalArgumentException if {@code time} is negative
     */
    static Poster at(Duration time) {
        Objects.requireNonNull(time, "time");
        if (time.isNegative()) {
            throw new IllegalArgumentException("a poster's time cannot be negative: " + time);
        }
        // To the microsecond, the unit a stream is moved by, so the time read back is the one used.
        Duration micros = Duration.ofNanos(time.toNanos() / 1_000 * 1_000);
        return micros.isZero() ? FirstPicture.INSTANCE : new At(micros);
    }

    /**
     * A picture of the application's, fitted to the box as the video's own pictures are. The stream
     * is not read until playback starts.
     *
     * @param image the picture, which stays the caller's
     * @return the poster
     */
    static Poster image(Image image) {
        return new Still(Objects.requireNonNull(image, "image"));
    }

    /** Nothing: the bar colour until playback shows a picture. The stream is not read before. */
    static Poster none() {
        return None.INSTANCE;
    }

    /** The stream's first picture. */
    final class FirstPicture implements Poster {
        static final FirstPicture INSTANCE = new FirstPicture();

        private FirstPicture() {
        }

        @Override
        public String toString() {
            return "Poster.firstPicture()";
        }
    }

    /** The picture at a time, made by {@link Poster#at}. */
    final class At implements Poster {
        private final Duration time;

        private At(Duration time) {
            this.time = time;
        }

        /** @return where the picture is on the stream's own timeline, above zero, to the microsecond */
        public Duration time() {
            return time;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof At at && at.time.equals(time);
        }

        @Override
        public int hashCode() {
            return time.hashCode();
        }

        @Override
        public String toString() {
            return "Poster.at(" + time + ")";
        }
    }

    /** A picture of the application's, made by {@link Poster#image}. */
    final class Still implements Poster {
        private final Image image;

        private Still(Image image) {
            this.image = image;
        }

        /** @return the picture, which stays the application's */
        public Image image() {
            return image;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Still still && still.image.equals(image);
        }

        @Override
        public int hashCode() {
            return image.hashCode();
        }

        @Override
        public String toString() {
            return "Poster.image(" + image + ")";
        }
    }

    /** Nothing but the bar colour. */
    final class None implements Poster {
        static final None INSTANCE = new None();

        private None() {
        }

        @Override
        public String toString() {
            return "Poster.none()";
        }
    }
}
