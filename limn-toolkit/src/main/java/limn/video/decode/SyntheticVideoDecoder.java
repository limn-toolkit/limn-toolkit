package limn.video.decode;

import limn.video.VideoDecoder;
import limn.video.VideoStreamSource;
import limn.video.Videos;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * A decoder with nothing to decode: it draws its pictures, so a video is available on any machine,
 * in every layout, under every colour interpretation and at any size, with no file to ship and
 * nothing to go missing. Every sample of every picture is a documented function of its coordinates,
 * which is what makes a rendered frame comparable against arithmetic instead of against a reference
 * image somebody has to eyeball.
 *
 * <p><b>The path is a description, not a location.</b> This decoder claims any path whose file name
 * ends in {@code .synth} and reads the rest of that name as a {@link SyntheticSpec}; it never opens,
 * reads, stats or creates anything. The file need not exist and normally does not.
 *
 * <pre>{@code
 * Videos.installDecoder(new SyntheticVideoDecoder());
 * SyntheticSpec spec = SyntheticSpec.of(640, 360).withPattern(SyntheticPattern.COUNTER);
 * try (VideoStreamSource source = Videos.open(spec.path())) {
 *     // ...
 * }
 * }</pre>
 *
 * <p>Going through {@link Videos} rather than calling {@link #open(SyntheticSpec)} is not ceremony:
 * it is what proves the facade, the install order and a player's ordinary open path with no real
 * decoder installed at all. {@link #open(SyntheticSpec)} exists for the caller that already holds a
 * spec and would otherwise format a name only to have it parsed straight back.
 *
 * <p>Stateless and immutable; one instance serves every stream, from any thread.
 */
public final class SyntheticVideoDecoder implements VideoDecoder {

    @Override
    public String name() {
        return "synthetic";
    }

    /**
     * @return whether {@code file}'s name ends with {@code .synth}, ignoring case. The filesystem is
     *         not touched and the rest of the name is not parsed, so a name that ends correctly and
     *         is malformed inside is claimed here and rejected (with a message that says what is
     *         wrong with it) by {@link #openStream}.
     */
    @Override
    public boolean supports(Path file) {
        if (file == null) {
            return false;
        }
        Path name = file.getFileName();
        return name != null
                && name.toString().toLowerCase(Locale.ROOT).endsWith(SyntheticSpec.EXTENSION);
    }

    /**
     * Opens the stream described by {@code file}'s name.
     *
     * @throws IllegalArgumentException if the name is not a readable {@link SyntheticSpec}
     * @throws NullPointerException     if {@code file} is null
     */
    @Override
    public VideoStreamSource openStream(Path file) {
        Objects.requireNonNull(file, "file");
        Path name = file.getFileName();
        if (name == null) {
            throw new IllegalArgumentException(file + " has no file name to read a spec from");
        }
        return open(SyntheticSpec.parse(name.toString()));
    }

    /**
     * Opens the stream {@code spec} describes, without going through a path. The caller owns the
     * source and closes it; its pictures' memory is allocated here, once, and reused for the life of
     * the stream.
     *
     * @throws NullPointerException if {@code spec} is null
     */
    public static VideoStreamSource open(SyntheticSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return new SyntheticSource(spec);
    }
}
