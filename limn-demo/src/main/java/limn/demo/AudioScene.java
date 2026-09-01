package limn.demo;

import limn.components.Button;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.Slider;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.math.Vec3;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.sound.AudioBus;
import limn.sound.AudioClip;
import limn.sound.PlayOptions;
import limn.sound.Playback;
import limn.sound.Sounds;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Expressive-audio showcase, everything procedural (no asset files): pitch
 * variation and pan on {@link PlayOptions}, live {@link AudioBus} mixer
 * gains, HIGH-priority music streamed from a generated WAV (so an effects
 * burst cannot steal its voice), and a click-to-place 3D positional emitter
 * around the {@link Sounds#setListener listener}.
 */
final class AudioScene {

    private AudioScene() {
    }

    /** Standalone {@code --scene audio}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** The subtree, reusable as a kitchen-sink tab. */
    static Widget content() {
        Column column = new Column();
        column.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);

        // Shared clips, synthesized once.
        AudioClip blip = AudioClip.tone(880f, 0.09f, 0.5f);
        AudioClip boom = AudioClip.tone(160f, 0.25f, 0.6f);

        column.add(heading("Effects: pitch + pan (PlayOptions)"));
        column.add(new Label("Every press plays the SAME clip: withPitch(random) "
                + "de-repetifies it, the slider pans it left/right (mono clips).")
                .setMuted(true).setWrap(true));
        Slider pan = new Slider(-100, 100).setValue(0);
        // Pan is physical space, not reading order: -100 is the LEFT speaker in any language,
        // so the slider's axis does not mirror (docs/design/direction-axis.md).
        pan.setLayoutDirection(limn.scene.LayoutDirection.LTR);
        pan.setTooltip("Pan for the next effect: -100 left … 100 right");
        Button playBlip = new Button("Blip (random pitch)");
        playBlip.onAction(() -> Sounds.play(blip, PlayOptions.DEFAULTS
                .withPitch(0.8f + (float) Math.random() * 0.5f)
                .withPan(pan.value() / 100f)));
        Button playBoom = new Button("Boom").setSecondary(true);
        playBoom.onAction(() -> Sounds.play(boom, PlayOptions.DEFAULTS
                .withPitch(0.9f + (float) Math.random() * 0.2f)
                .withPan(pan.value() / 100f)));
        Row sfxRow = new Row();
        sfxRow.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        sfxRow.add(playBlip);
        sfxRow.add(playBoom);
        sfxRow.add(new Label("Pan:").setMuted(true));
        sfxRow.add(Expanded.of(pan, 1));
        column.add(sfxRow);

        column.add(heading("Mixer buses (live)"));
        column.add(new Label("The options-screen model: master × bus × play gains, "
                + "applied live to everything already sounding. Drag while the music plays.")
                .setMuted(true).setWrap(true));
        column.add(mixerRow("Master", value -> Sounds.setMasterGain(value)));
        column.add(mixerRow("Music", value -> Sounds.setBusGain(AudioBus.MUSIC, value)));
        column.add(mixerRow("Effects", value -> Sounds.setBusGain(AudioBus.SFX, value)));

        column.add(heading("Streamed music + priority"));
        column.add(new Label("The track is a generated WAV streamed from disk (decoded "
                + "incrementally, no whole-clip on the heap) on the MUSIC bus at HIGH "
                + "priority: \"Burst 30 blips\" floods every voice, the music survives.")
                .setMuted(true).setWrap(true));
        MusicPanel music = new MusicPanel();
        column.add(music);
        Button burst = new Button("Burst 30 blips").setSecondary(true);
        burst.setTooltip("30 NORMAL-priority effects at once; HIGH music is never stolen");
        burst.onAction(() -> {
            for (int i = 0; i < 30; i++) {
                Sounds.play(blip, PlayOptions.DEFAULTS
                        .withGain(0.4f).withPitch(0.8f + (float) Math.random() * 0.6f));
            }
        });
        column.add(burst);

        column.add(heading("Positional 3D"));
        column.add(new Label("Click/drag to place a looping emitter around the listener "
                + "(the center dot, facing up-screen): left/right and distance are audible. "
                + "Click the center to stop.").setMuted(true).setWrap(true));
        column.add(new PositionalPad());

        return new ScrollView(column);
    }

    // Role, not setFont(theme.title): setFont pins MEDIUM's 20 pt whatever step the
    // subtree resolves to. The role picks the title token OF the resolved step.
    private static Label heading(String text) {
        return new Label(text).setRole(Label.Role.TITLE);
    }

    private static Row mixerRow(String caption, java.util.function.Consumer<Float> apply) {
        Slider slider = new Slider(0, 100).setValue(100);
        slider.onChange(value -> apply.accept(value / 100f));
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        Label label = new Label(caption).setMuted(true);
        row.add(new limn.scene.layout.SizedBox(70, limn.scene.layout.SizedBox.UNSET, label));
        row.add(Expanded.of(slider, 1));
        return row;
    }

    // ------------------------------------------------------- streamed music

    /** Play/pause/stop for the generated, streamed, looping track. */
    private static final class MusicPanel extends Widget {
        private final Button play = new Button("Play music");
        private final Button pause = new Button("Pause").setSecondary(true);
        private final Button stop = new Button("Stop").setSecondary(true);
        private final Label status = new Label("").setMuted(true);
        private final Row row = new Row();
        private Playback playback = Playback.NONE;
        private boolean paused;
        private boolean ticking;
        private double sinceStatus;

        MusicPanel() {
            row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
            row.add(play);
            row.add(pause);
            row.add(stop);
            row.add(status);
            add(row);
            play.onAction(() -> {
                playback.stop();
                paused = false;
                pause.setText("Pause");
                playback = Sounds.stream(musicFile(), PlayOptions.DEFAULTS
                        .withBus(AudioBus.MUSIC)
                        .withPriority(PlayOptions.Priority.HIGH)
                        .withLoop(true)
                        .withGain(0.8f));
                armTicker();
            });
            pause.onAction(() -> {
                if (paused) {
                    playback.resume();
                } else {
                    playback.pause();
                }
                paused = !paused;
                pause.setText(paused ? "Resume" : "Pause");
            });
            stop.onAction(this::stopMusic);
        }

        private void stopMusic() {
            playback.stop();
            playback = Playback.NONE;
            paused = false;
            pause.setText("Pause");
            status.setText("");
        }

        private void armTicker() {
            if (ticking || scene() == null) {
                return;
            }
            ticking = true;
            scene().addTicker(dt -> {
                if (!isShowing()) {
                    // Tab switches HIDE (setVisible), they don't detach; this
                    // is where "music must not keep looping behind another
                    // tab" actually happens (onDetached covers real removal).
                    stopMusic();
                    ticking = false;
                    return false; // re-armed by the next Play press
                }
                if (playback == Playback.NONE) {
                    status.setText("");
                    ticking = false;
                    return false; // stopped: no work left, stop waking frames
                }
                sinceStatus += dt;
                if (sinceStatus < 0.25) {
                    return true; // 4 Hz is plenty for a seconds counter
                }
                sinceStatus = 0;
                String text = playback.isPlaying() || paused
                        ? String.format(java.util.Locale.US,
                                "%.1f s (looping stream)", playback.positionSeconds())
                        : "";
                status.setText(text); // Label ignores unchanged/same-width text
                return true;
            });
        }

        @Override
        protected Size onMeasure(Constraints c) {
            Size inner = row.measure(c);
            return c.constrain(inner.width(), inner.height());
        }

        @Override
        protected void onLayout() {
            row.measure(Constraints.loose(width(), height()));
            row.layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onDetached() {
            stopMusic(); // real removal from the tree (hide is handled by the ticker)
        }
    }

    // The generated track, written once per process into the temp directory:
    // ~12 s of a soft stereo arpeggio, 16-bit PCM, honest streaming material.
    private static Path musicPath;

    private static synchronized Path musicFile() {
        if (musicPath != null && Files.exists(musicPath)) {
            return musicPath;
        }
        try {
            int rate = 44_100;
            float[] notes = {220f, 277.18f, 329.63f, 440f, 329.63f, 277.18f}; // A–C#–E arpeggio
            float noteSeconds = 0.5f;
            int loops = 4;
            int framesPerNote = (int) (rate * noteSeconds);
            int totalFrames = framesPerNote * notes.length * loops;
            ByteBuffer pcm = ByteBuffer.allocate(44 + totalFrames * 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            writeWavHeader(pcm, totalFrames, rate);
            for (int n = 0; n < notes.length * loops; n++) {
                float f = notes[n % notes.length];
                for (int i = 0; i < framesPerNote; i++) {
                    double t = i / (double) rate;
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
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException("writing demo music", error);
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

    // -------------------------------------------------------- positional pad

    /** Click-to-place looping emitter around a fixed centered listener. */
    private static final class PositionalPad extends Widget {
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
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 180);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            float radius = theme.tokensFor(this).radiusMedium();
            canvas.fillRoundRect(0, 0, width(), height(), radius, theme.surface);
            canvas.drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1,
                    radius, 1, theme.outline);
            float cx = width() / 2;
            float cy = height() - 24; // listener near the bottom, "facing up"
            canvas.fillCircle(cx, cy, 6, theme.primary);
            canvas.drawLine(cx, cy, cx, cy - 16, 2, theme.primary);
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
                    float lx = sceneToLocalX(event.x());
                    float ly = sceneToLocalY(event.y());
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

        /** Tab switches hide (not detach): a ticker kills the loop on hide. */
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
