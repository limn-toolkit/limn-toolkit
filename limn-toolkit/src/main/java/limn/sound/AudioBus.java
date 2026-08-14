package limn.sound;

import java.util.Objects;

/**
 * A mixer bus: a named volume group every playback belongs to. The effective
 * volume of a sound is {@code playGain × busGain × masterGain}, and bus/master
 * changes apply <em>live</em> to everything already sounding; this is the
 * options-screen "Music volume / Effects volume / Master" model.
 *
 * <p>Three conventional buses are predefined; {@link #of(String)} creates
 * additional ones (voice, ambience…). Buses are identity objects: hold and
 * reuse the instance; two {@code of("voice")} calls are two distinct buses.
 * Gains live in the {@link AudioEngine} (set via {@link Sounds#setBusGain}),
 * not on the bus object, so the bus itself is freely shareable.
 */
public final class AudioBus {

    /** Sound effects: the default bus of {@link PlayOptions#DEFAULTS}. */
    public static final AudioBus SFX = new AudioBus("sfx");

    /** Background music (long, looping, usually streamed). */
    public static final AudioBus MUSIC = new AudioBus("music");

    /** Interface feedback beeps/clicks. */
    public static final AudioBus UI = new AudioBus("ui");

    private final String name;

    private AudioBus(String name) {
        this.name = name;
    }

    /** Creates a custom bus (e.g. {@code "voice"}). Keep and reuse the instance. */
    public static AudioBus of(String name) {
        return new AudioBus(Objects.requireNonNull(name, "name"));
    }

    /** @return the bus's display name (diagnostics only; identity is the object) */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "AudioBus[" + name + "]";
    }
}
