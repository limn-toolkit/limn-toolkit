package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.Slider;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import limn.math.Vec3;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.sound.AudioBus;
import limn.sound.AudioClip;
import limn.sound.AudioStreamSource;
import limn.sound.PlayOptions;
import limn.sound.Playback;
import limn.sound.Sounds;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * The Sound page, everything procedural (no asset files): two effects played at a random pitch
 * and a chosen pan, a mixer with a level and a mute for each bus, music streamed from a file this
 * page writes, with its place in the track, and a positional sound placed around the listener.
 */
final class AudioScene {

    /** What the page says, ready for translation. */
    static final class Text {
        static final I18nString EFFECTS = new I18nString("showcase.sound.effects", "Effects");
        static final I18nString CHIME = new I18nString("showcase.sound.chime", "Chime");
        static final I18nString THUD = new I18nString("showcase.sound.thud", "Thud");
        static final I18nString PAN = new I18nString("showcase.sound.pan", "Pan");
        static final I18nString MIXER = new I18nString("showcase.sound.mixer", "Mixer");
        static final I18nString MASTER = new I18nString("showcase.sound.master", "Master");
        static final I18nString MUSIC = new I18nString("showcase.sound.music", "Music");
        static final I18nString EFFECTS_BUS = new I18nString("showcase.sound.effectsBus", "Effects");
        static final I18nString MUTE = new I18nString("showcase.sound.mute", "Mute");
        static final I18nString PLAY = new I18nString("showcase.sound.play", "Play");
        static final I18nString PAUSE = new I18nString("showcase.sound.pause", "Pause");
        static final I18nString RESUME = new I18nString("showcase.sound.resume", "Resume");
        static final I18nString STOP = new I18nString("showcase.sound.stop", "Stop");
        static final I18nString BURST = new I18nString("showcase.sound.burst", "Burst");
        static final I18nString BURST_TIP = new I18nString("showcase.sound.burstTip",
                "Thirty effects at once; the music keeps its voice");
        static final I18nString POSITION = new I18nString("showcase.sound.position", "Position");
        static final I18nString POSITIONAL = new I18nString("showcase.sound.positional", "Positional");
        static final I18nString POSITIONAL_HINT = new I18nString("showcase.sound.positionalHint",
                "Click or drag to place the sound; click the centre to stop it.");
        static final I18nString PAD = new I18nString("showcase.sound.pad", "Sound around the listener");

        private Text() {
        }
    }

    /** How wide a mixer's slider is: long enough to set a level, short enough to read as one. */
    private static final float SLIDER_WIDTH = 400;

    private AudioScene() {
    }

    /** Standalone {@code --scene audio}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        return scene;
    }

    /** The subtree, reusable as a page of the showcase. */
    static Widget<?> content() {
        Column column = Page.column();
        // The captions before the sliders, as wide as the longest of them in the language shown
        // so the sliders start on one line: a width fixed for English, 72 pt, cut pt-BR's
        // "Panorâmica" short.
        Page.SameWidth captions = new Page.SameWidth();

        // Shared clips, synthesized once.
        AudioClip chime = AudioClip.tone(880f, 0.09f, 0.5f);
        AudioClip thud = AudioClip.tone(160f, 0.25f, 0.6f);

        // --- Effects: one clip at another pitch each time, panned by the slider ------------
        column.add(Page.firstSection(Text.EFFECTS));
        Slider pan = new Slider(-100, 100).setValue(0);
        // Pan is physical space, not reading order: -100 is the LEFT speaker in any language,
        // so the slider's axis does not mirror (docs/design/direction-axis.md).
        pan.setLayoutDirection(limn.scene.LayoutDirection.LTR);
        Button playChime = new Button(Text.CHIME).setStyle(Button.Style.SECONDARY);
        playChime.onAction(() -> Sounds.play(chime, PlayOptions.DEFAULTS
                .withPitch(0.8f + (float) Math.random() * 0.5f)
                .withPan(pan.value() / 100f)));
        Button playThud = new Button(Text.THUD).setStyle(Button.Style.SECONDARY);
        playThud.onAction(() -> Sounds.play(thud, PlayOptions.DEFAULTS
                .withPitch(0.9f + (float) Math.random() * 0.2f)
                .withPan(pan.value() / 100f)));
        column.add(Page.row(12, playChime, playThud));
        column.add(levelRow(captions, Text.PAN, pan, null, null));

        // --- Mixer: a level and a mute for each bus ---------------------------------------
        column.add(Page.section(Text.MIXER));
        column.add(mixerRow(captions, Text.MASTER, Sounds::setMasterGain));
        column.add(mixerRow(captions, Text.MUSIC, value -> Sounds.setBusGain(AudioBus.MUSIC, value)));
        column.add(mixerRow(captions, Text.EFFECTS_BUS, value -> Sounds.setBusGain(AudioBus.SFX, value)));

        // --- Music streamed from a file, with its place in the track ----------------------
        column.add(Page.section(Text.MUSIC));
        Button burst = new Button(Text.BURST).setStyle(Button.Style.SECONDARY);
        burst.setTooltip(Text.BURST_TIP);
        burst.onAction(() -> {
            for (int i = 0; i < 30; i++) {
                Sounds.play(chime, PlayOptions.DEFAULTS
                        .withGain(0.4f).withPitch(0.8f + (float) Math.random() * 0.6f));
            }
        });
        MusicPanel music = new MusicPanel(captions, burst);
        column.add(music);

        // --- A sound placed around the listener ---------------------------------------------
        column.add(Page.section(Text.POSITIONAL));
        column.add(Page.hint(Text.POSITIONAL_HINT));
        // Ending where the sliders above it end, whatever the captions' width.
        Row pad = new Row();
        pad.add(captions.wider(12 + SLIDER_WIDTH, new PositionalPad()));
        pad.add(Expanded.spacer(1));
        column.add(pad);

        return column; // the page scrolls it
    }

    /** A caption, a slider of {@link #SLIDER_WIDTH}, and what else the row carries after it. */
    private static Row levelRow(Page.SameWidth captions, I18nString caption, Slider slider, Widget<?> value,
                                Widget<?> more) {
        Label label = new Label(caption).setMuted(true).setLabelFor(slider);
        Row row = Page.row(12, captions.box(label),
                new SizedBox(SLIDER_WIDTH, SizedBox.UNSET, slider));
        if (value != null) {
            row.add(value);
        }
        if (more != null) {
            row.add(more);
        }
        row.add(Expanded.spacer(1));
        return row;
    }

    /** One bus: its level, the level as a number, and a mute that keeps the level for later. */
    private static Row mixerRow(Page.SameWidth captions, I18nString caption, Consumer<Float> apply) {
        Slider slider = new Slider(0, 100).setValue(100);
        Label value = new Label(percent(100)).setMuted(true);
        Checkbox mute = new Checkbox(Text.MUTE);
        slider.onChange(level -> {
            value.setText(percent(level));
            if (!mute.isChecked()) {
                apply.accept(level / 100f);
            }
        });
        mute.onChange(on -> apply.accept(on ? 0f : slider.value() / 100f));
        mute.setAccessibleDescription(caption);
        return levelRow(captions, caption, slider, new SizedBox(48, SizedBox.UNSET, value), mute);
    }

    private static String percent(float level) {
        return NumberFormats.percent(0).apply(level / 100.0);
    }

    // ------------------------------------------------------- streamed music

    /** Play, pause, stop and a burst on one row; the place in the track on the next. */
    private static final class MusicPanel extends Widget<MusicPanel> {
        private final Button play = new Button(Text.PLAY);
        private final Button pause = new Button(Text.PAUSE).setStyle(Button.Style.SECONDARY);
        private final Button stop = new Button(Text.STOP).setStyle(Button.Style.SECONDARY);
        private final Slider position = new Slider(0, TRACK_SECONDS);
        private final Label clock = new Label(clock(0)).setMuted(true);
        private final Column rows = new Column();
        private Playback playback = Playback.NONE;
        private boolean paused;
        private boolean ticking;
        private boolean dragging;
        private double sinceClock;

        MusicPanel(Page.SameWidth captions, Button burst) {
            rows.gap(12).crossAlignment(limn.scene.layout.Flex.CrossAlignment.STRETCH);
            Row buttons = Page.row(12, play, pause, stop, burst);
            buttons.add(Expanded.spacer(1));
            rows.add(buttons);
            position.setEnabled(false);
            // A track's time runs left to right in every language, as a player's bar does
            // (MediaControls declares the same), and so does the clock that reads it.
            position.setLayoutDirection(limn.scene.LayoutDirection.LTR);
            clock.setLayoutDirection(limn.scene.LayoutDirection.LTR);
            rows.add(levelRow(captions, Text.POSITION, position, clock, null));
            add(rows);
            pause.setEnabled(false);
            stop.setEnabled(false);
            play.onAction(() -> {
                playback.stop();
                paused = false;
                pause.setText(Text.PAUSE);
                // The page's own stream over the file it wrote, because the file decoders the
                // toolkit installs stream a WAV forward only, and this one can be moved.
                playback = Sounds.stream(new WavFileTrack(musicFile()), PlayOptions.DEFAULTS
                        .withBus(AudioBus.MUSIC)
                        .withPriority(PlayOptions.Priority.HIGH)
                        .withLoop(true)
                        .withGain(0.8f));
                pause.setEnabled(true);
                stop.setEnabled(true);
                position.setEnabled(playback.canSeek());
                armTicker();
            });
            pause.onAction(() -> {
                if (paused) {
                    playback.resume();
                } else {
                    playback.pause();
                }
                paused = !paused;
                pause.setText(paused ? Text.RESUME : Text.PAUSE);
            });
            stop.onAction(this::stopMusic);
            position.onChange(seconds -> {
                dragging = true;
                clock.setText(clock(seconds));
            });
            position.onCommit(seconds -> {
                dragging = false;
                playback.seek((long) (seconds * 1_000_000));
            });
        }

        private void stopMusic() {
            playback.stop();
            playback = Playback.NONE;
            paused = false;
            pause.setText(Text.PAUSE);
            pause.setEnabled(false);
            stop.setEnabled(false);
            position.setEnabled(false);
            position.setValue(0);
            clock.setText(clock(0));
        }

        private void armTicker() {
            if (ticking || scene() == null) {
                return;
            }
            ticking = true;
            scene().addRealTimeTicker(dt -> {
                if (!isShowing()) {
                    // Pages are hidden, not detached, when another is chosen: this is where the
                    // music stops behind another page (onDetached covers real removal).
                    stopMusic();
                    ticking = false;
                    return false; // re-armed by the next Play
                }
                if (playback == Playback.NONE) {
                    ticking = false;
                    return false; // stopped: no work left, no more frames asked for
                }
                sinceClock += dt;
                if (sinceClock < 0.25 || dragging) {
                    return true; // four times a second is plenty for a clock in seconds
                }
                sinceClock = 0;
                float at = (float) (playback.positionSeconds() % TRACK_SECONDS);
                if (at != position.value()) {
                    position.setValue(at);
                }
                clock.setText(clock(at));
                return true;
            });
        }

        private static String clock(double seconds) {
            return mmss(seconds) + " / " + mmss(TRACK_SECONDS);
        }

        /**
         * Minutes and seconds in the language's digits, as the video's controls write them: under
         * the locale the toolkit writes numbers in, which names those digits.
         */
        private static String mmss(double seconds) {
            int whole = (int) Math.floor(seconds);
            return String.format(limn.i18n.I18n.numberLocale(), "%d:%02d", whole / 60, whole % 60);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            Size inner = rows.measure(c);
            return c.constrain(inner.width(), inner.height());
        }

        @Override
        protected void onLayout() {
            rows.measure(Constraints.tight(width(), height()));
            rows.layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onDetached() {
            stopMusic(); // real removal from the tree (hide is handled by the ticker)
        }
    }

    // The generated track, written once per process into the temporary directory: twelve seconds
    // of a soft stereo arpeggio, 16-bit PCM, honest streaming material.
    private static final int RATE = 44_100;
    private static final float[] NOTES = {220f, 277.18f, 329.63f, 440f, 329.63f, 277.18f}; // A, C#, E
    private static final float NOTE_SECONDS = 0.5f;
    private static final int LOOPS = 4;
    private static final int TRACK_SECONDS = (int) (NOTES.length * LOOPS * NOTE_SECONDS);
    private static final int HEADER = 44;
    private static Path musicPath;

    private static synchronized Path musicFile() {
        if (musicPath != null && Files.exists(musicPath)) {
            return musicPath;
        }
        try {
            int framesPerNote = (int) (RATE * NOTE_SECONDS);
            int totalFrames = framesPerNote * NOTES.length * LOOPS;
            ByteBuffer pcm = ByteBuffer.allocate(HEADER + totalFrames * 4).order(ByteOrder.LITTLE_ENDIAN);
            writeWavHeader(pcm, totalFrames, RATE);
            for (int n = 0; n < NOTES.length * LOOPS; n++) {
                float f = NOTES[n % NOTES.length];
                for (int i = 0; i < framesPerNote; i++) {
                    double t = i / (double) RATE;
                    // Raised-cosine envelope kills note-boundary clicks.
                    double env = 0.5 * (1 - Math.cos(2 * Math.PI * i / (framesPerNote - 1.0)));
                    double left = Math.sin(2 * Math.PI * f * t);
                    double right = Math.sin(2 * Math.PI * (f * 1.003) * t); // slight detune
                    short l = (short) (left * env * 0.30 * Short.MAX_VALUE);
                    short r = (short) (right * env * 0.30 * Short.MAX_VALUE);
                    pcm.putShort(l).putShort(r);
                }
            }
            Path file = Files.createTempFile("limn-demo-music", ".wav");
            Files.write(file, pcm.array());
            file.toFile().deleteOnExit();
            musicPath = file;
            return file;
        } catch (IOException error) {
            throw new UncheckedIOException("writing demo music", error);
        }
    }

    private static void writeWavHeader(ByteBuffer out, int frames, int rate) {
        int dataBytes = frames * 4; // stereo 16-bit
        out.put("RIFF".getBytes()).putInt(36 + dataBytes).put("WAVE".getBytes());
        out.put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 2)
                .putInt(rate).putInt(rate * 4).putShort((short) 4).putShort((short) 16);
        out.put("data".getBytes()).putInt(dataBytes);
    }

    /**
     * The track read from its file as it plays, a buffer at a time, and movable: the place a read
     * starts from is arithmetic over the file's fixed layout (stereo, 16 bits, after a 44-byte
     * header), so a seek is a new file position and nothing else.
     */
    private static final class WavFileTrack implements AudioStreamSource {
        private final FileChannel file;
        private final long frames;
        private long frame;
        private ByteBuffer bytes = ByteBuffer.allocate(0);

        WavFileTrack(Path path) {
            try {
                file = FileChannel.open(path, StandardOpenOption.READ);
                frames = (file.size() - HEADER) / 4;
            } catch (IOException error) {
                throw new UncheckedIOException("opening demo music", error);
            }
        }

        @Override
        public int channels() {
            return 2;
        }

        @Override
        public int sampleRate() {
            return RATE;
        }

        @Override
        public int readFrames(short[] out, int maxFrames) {
            int count = (int) Math.min(maxFrames, frames - frame);
            if (count <= 0) {
                return 0;
            }
            if (bytes.capacity() < count * 4) {
                bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
            }
            bytes.clear().limit(count * 4);
            try {
                long at = HEADER + frame * 4;
                while (bytes.hasRemaining()) {
                    int read = file.read(bytes, at + bytes.position());
                    if (read < 0) {
                        break;
                    }
                }
            } catch (IOException error) {
                return 0; // a file gone from under the stream ends it
            }
            bytes.flip();
            int got = bytes.remaining() / 4;
            for (int i = 0; i < got * 2; i++) {
                out[i] = bytes.getShort();
            }
            frame += got;
            return got;
        }

        @Override
        public void reset() {
            frame = 0;
        }

        @Override
        public boolean canSeek() {
            return true;
        }

        @Override
        public void seek(long micros) {
            if (micros < 0) {
                throw new IllegalArgumentException("micros " + micros);
            }
            frame = Math.min(frames, micros * RATE / 1_000_000L);
        }

        @Override
        public void close() {
            try {
                file.close();
            } catch (IOException ignored) {
                // closing a read-only file: nothing was written to lose
            }
        }
    }

    // -------------------------------------------------------- positional pad

    /** Click-to-place looping emitter around a fixed listener near the bottom, facing up. */
    private static final class PositionalPad extends Widget<PositionalPad> {
        private static final float WORLD = 6f; // half-extent in audio units
        private final AudioClip ping = AudioClip.tone(660f, 0.35f, 0.5f);
        private Playback emitter = Playback.NONE;
        private float emitterX = Float.NaN;
        private float emitterZ;
        private boolean suppressDrag; // press on the center stops; its jitter must not re-place
        private boolean ticking;

        PositionalPad() {
            // Listener at the origin facing "into the screen" (up the pad),
            // matching the pad's top = farther away.
            Sounds.setListener(Vec3.ZERO, new Vec3(0, 0, -1), new Vec3(0, 1, 0));
            setAccessibleName(Text.PAD);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.hasBoundedWidth() ? c.maxWidth() : 480, 180);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            float radius = theme.tokensFor(this).radiusMedium();
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), radius, theme.surface(),
                    1, theme.outline());
            float cx = width() / 2;
            float cy = height() - 24; // listener near the bottom, "facing up"
            canvas.fillCircle(cx, cy, 6, theme.primary());
            canvas.drawLine(cx, cy, cx, cy - 16, 2, theme.primary());
            if (!Float.isNaN(emitterX)) {
                float ex = cx + emitterX / WORLD * (width() / 2 - 20);
                float ey = cy + emitterZ / WORLD * (height() - 48);
                canvas.fillCircle(ex, ey, 8, new Color(0.95f, 0.55f, 0.20f, 1f));
            }
        }

        @Override
        protected void onMouseEvent(limn.scene.event.MouseEvent event) {
            switch (event.type()) {
                case RELEASE -> suppressDrag = false;
                case PRESS, DRAG -> {
                    limn.math.Vec2 local = sceneToLocal(event.x(), event.y());
                    float lx = local.x();
                    float ly = local.y();
                    float cx = width() / 2;
                    float cy = height() - 24;
                    float wx = (lx - cx) / (width() / 2 - 20) * WORLD;
                    float wz = (ly - cy) / (height() - 48) * WORLD; // up = negative z (ahead)
                    if (event.type() == limn.scene.event.MouseEvent.Type.PRESS) {
                        suppressDrag = Math.abs(lx - cx) < 14 && Math.abs(ly - cy) < 14;
                        if (suppressDrag) { // center press: stop the emitter
                            emitter.stop();
                            emitter = Playback.NONE;
                            emitterX = Float.NaN;
                            invalidate();
                            event.consume();
                            return;
                        }
                    } else if (suppressDrag) {
                        event.consume();
                        return; // jitter after a center press must not re-place
                    }
                    emitterX = Math.max(-WORLD, Math.min(wx, WORLD));
                    emitterZ = Math.max(-WORLD, Math.min(wz, 0.5f));
                    if (emitter == Playback.NONE || !emitter.isPlaying()) {
                        // HIGH priority: a continuously-looping voice sits at the
                        // LRU front and a NORMAL effects burst would steal it.
                        emitter = Sounds.play(ping, PlayOptions.DEFAULTS
                                .withLoop(true).withGain(0.7f)
                                .withPriority(PlayOptions.Priority.HIGH)
                                .at(emitterX, 0, emitterZ));
                        armStopOnHide();
                    } else {
                        emitter.setPosition(emitterX, 0, emitterZ);
                    }
                    invalidate();
                    event.consume();
                }
                default -> {
                }
            }
        }

        /** Pages are hidden, not detached, when another is chosen: a ticker ends the loop then. */
        private void armStopOnHide() {
            if (ticking || scene() == null) {
                return;
            }
            ticking = true;
            scene().addTicker(dt -> {
                if (!isShowing()) {
                    stopEmitter();
                    ticking = false;
                    return false;
                }
                if (emitter == Playback.NONE) {
                    ticking = false;
                    return false; // stopped by the center press: no work left
                }
                return true;
            });
        }

        private void stopEmitter() {
            emitter.stop();
            emitter = Playback.NONE;
            emitterX = Float.NaN;
        }

        @Override
        protected void onDetached() {
            stopEmitter(); // real removal (hide is handled by the ticker)
        }
    }
}
