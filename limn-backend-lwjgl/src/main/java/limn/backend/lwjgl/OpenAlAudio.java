package limn.backend.lwjgl;

import limn.math.Vec3;
import limn.sound.AudioBus;
import limn.sound.AudioClip;
import limn.sound.AudioEngine;
import limn.sound.AudioStreamSource;
import limn.sound.PlayOptions;
import limn.sound.Playback;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.lang.System.Logger.Level;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.openal.AL10.AL_BUFFER;
import static org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED;
import static org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED;
import static org.lwjgl.openal.AL10.AL_FALSE;
import static org.lwjgl.openal.AL10.AL_FORMAT_MONO16;
import static org.lwjgl.openal.AL10.AL_FORMAT_STEREO16;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_LOOPING;
import static org.lwjgl.openal.AL10.AL_ORIENTATION;
import static org.lwjgl.openal.AL10.AL_PAUSED;
import static org.lwjgl.openal.AL10.AL_PITCH;
import static org.lwjgl.openal.AL10.AL_PLAYING;
import static org.lwjgl.openal.AL10.AL_POSITION;
import static org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE;
import static org.lwjgl.openal.AL10.AL_SOURCE_STATE;
import static org.lwjgl.openal.AL10.AL_TRUE;
import static org.lwjgl.openal.AL10.alBufferData;
import static org.lwjgl.openal.AL10.alDeleteBuffers;
import static org.lwjgl.openal.AL10.alDeleteSources;
import static org.lwjgl.openal.AL10.alGenBuffers;
import static org.lwjgl.openal.AL10.alGenSources;
import static org.lwjgl.openal.AL10.alGetSourcef;
import static org.lwjgl.openal.AL10.alGetSourcei;
import static org.lwjgl.openal.AL10.alListener3f;
import static org.lwjgl.openal.AL10.alListenerfv;
import static org.lwjgl.openal.AL10.alSource3f;
import static org.lwjgl.openal.AL10.alSourcePause;
import static org.lwjgl.openal.AL10.alSourcePlay;
import static org.lwjgl.openal.AL10.alSourceQueueBuffers;
import static org.lwjgl.openal.AL10.alSourceStop;
import static org.lwjgl.openal.AL10.alSourceUnqueueBuffers;
import static org.lwjgl.openal.AL10.alSourcef;
import static org.lwjgl.openal.AL10.alSourcei;
import static org.lwjgl.openal.AL11.AL_SEC_OFFSET;
import static org.lwjgl.openal.ALC10.alcCloseDevice;
import static org.lwjgl.openal.ALC10.alcCreateContext;
import static org.lwjgl.openal.ALC10.alcDestroyContext;
import static org.lwjgl.openal.ALC10.alcMakeContextCurrent;
import static org.lwjgl.openal.ALC10.alcOpenDevice;

/**
 * OpenAL implementation of {@link AudioEngine}. Owns a single device/context
 * (opened lazily on first use) and mixes clips through a small pool of
 * reusable sources plus a few dedicated streaming sources. Each
 * {@link AudioClip} is uploaded to a device buffer once and cached by object
 * identity; streams decode incrementally on a daemon service thread through a
 * short ring of queued buffers.
 *
 * <p>Expressive playback ({@link PlayOptions}): per-voice pitch, pan (mono,
 * via a listener-relative position), 3D position (mono, with the
 * {@link #setListener listener}), mixer buses with live
 * {@code play × bus × master} gains, and priority-aware voice stealing:
 * a burst of effects steals other effects, never HIGH-priority music.
 *
 * <p>Best-effort: if no audio device is available (headless/CI) initialization
 * fails silently and every play returns {@link Playback#NONE}. All AL calls
 * are serialized on this instance's monitor, so the engine, the returned
 * {@link Playback} handles and the streaming thread are safe together.
 */
final class OpenAlAudio implements AudioEngine, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(OpenAlAudio.class.getName());

    /** Upper bound on simultaneously sounding pooled voices. */
    private static final int MAX_VOICES = 24;

    /** Device buffers are ~2 bytes/sample: dynamically created clips must not pin forever. */
    private static final int MAX_CACHED_CLIPS = 64;

    /** Dedicated streaming slots (music + a couple of long ambiences). */
    private static final int MAX_STREAMS = 4;

    /** Frames per streaming chunk (~0.19 s at 44.1 kHz) and chunks in flight. */
    private static final int STREAM_CHUNK_FRAMES = 8192;
    private static final int STREAM_BUFFERS = 3;

    private final Map<AudioClip, Integer> bufferByClip = new IdentityHashMap<>();
    private final java.util.ArrayDeque<AudioClip> clipOrder = new java.util.ArrayDeque<>();
    private final List<Voice> voices = new ArrayList<>();
    private final List<Stream> streams = new ArrayList<>();
    // AudioBus has identity equality, so a HashMap keys by bus instance.
    private final Map<AudioBus, Float> busGains = new HashMap<>();
    private float masterGain = 1f;

    private long device;
    private long context;
    private boolean initialized;
    private boolean failed;
    private boolean closed;
    private Thread streamThread;
    /**
     * The direct staging buffer every {@code alBufferData} reads from. Touched only under the
     * monitor (priming in {@link #playStream}, phase C and the seek refill), which is what makes
     * one shared buffer safe where one shared decode scratch was not: see {@link Stream#scratch}.
     */
    private ShortBuffer streamUpload;

    @Override
    public synchronized Playback play(AudioClip clip, float gain, boolean loop) {
        return play(clip, PlayOptions.DEFAULTS.withGain(clamp01(gain)).withLoop(loop));
    }

    @Override
    public synchronized Playback play(AudioClip clip, PlayOptions options) {
        if (!ensureInitialized()) {
            return Playback.NONE;
        }
        try {
            int buffer = bufferFor(clip);
            Voice voice = acquireVoice(options.priority().ordinal());
            if (voice == null) {
                return Playback.NONE; // every voice is busy with higher priority
            }
            int token = ++voice.generation;
            voice.playGain = options.gain();
            voice.bus = options.bus();
            voice.priority = options.priority().ordinal();
            voice.mono = clip.channels() == 1;
            voice.positional = options.position() != null && voice.mono;
            int source = voice.source;
            alSourcei(source, AL_BUFFER, buffer);
            alSourcef(source, AL_GAIN, effectiveGain(voice.playGain, voice.bus));
            alSourcef(source, AL_PITCH, options.pitch());
            alSourcei(source, AL_LOOPING, options.loop() ? AL_TRUE : AL_FALSE);
            applySpatial(source, voice.mono, options.pan(), options.position());
            alSourcePlay(source);
            return new OpenAlPlayback(voice, token);
        } catch (Throwable error) {
            LOG.log(Level.DEBUG, "audio playback failed", error);
            return Playback.NONE;
        }
    }

    @Override
    public synchronized boolean isAvailable() {
        return ensureInitialized();
    }

    @Override
    public synchronized void setMasterGain(float gain) {
        masterGain = clamp01(gain);
        refreshGains();
    }

    @Override
    public synchronized void setBusGain(AudioBus bus, float gain) {
        busGains.put(bus, clamp01(gain));
        refreshGains();
    }

    @Override
    public synchronized void setListener(Vec3 position, Vec3 forward, Vec3 up) {
        if (!ensureInitialized()) {
            return;
        }
        alListener3f(AL_POSITION, position.x(), position.y(), position.z());
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            alListenerfv(AL_ORIENTATION, stack.floats(
                    forward.x(), forward.y(), forward.z(), up.x(), up.y(), up.z()));
        }
    }

    // ------------------------------------------------------------- internals

    private boolean ensureInitialized() {
        if (closed) {
            return false; // shutdown is final: play() must not resurrect the device
        }
        if (initialized) {
            return true;
        }
        if (failed) {
            return false;
        }
        try {
            initialize();
        } catch (Throwable error) {
            failed = true;
            LOG.log(Level.DEBUG, "audio unavailable", error);
        }
        return initialized;
    }

    private void initialize() {
        device = alcOpenDevice((java.nio.ByteBuffer) null);
        if (device == 0L) {
            failed = true;
            return;
        }
        ALCCapabilities alcCaps = ALC.createCapabilities(device);
        context = alcCreateContext(device, (java.nio.IntBuffer) null);
        if (context == 0L || !alcMakeContextCurrent(context)) {
            failed = true;
            return;
        }
        AL.createCapabilities(alcCaps);
        initialized = true;
    }

    private float effectiveGain(float playGain, AudioBus bus) {
        float busGain = busGains.getOrDefault(bus, 1f);
        return clamp01(playGain) * busGain * masterGain;
    }

    /** Re-applies {@code play × bus × master} to everything currently sounding. */
    private void refreshGains() {
        if (!initialized) {
            return;
        }
        for (Voice voice : voices) {
            if (voice.bus != null && isActive(voice.source)) {
                alSourcef(voice.source, AL_GAIN, effectiveGain(voice.playGain, voice.bus));
            }
        }
        for (Stream stream : streams) {
            if (!stream.finished) {
                alSourcef(stream.source, AL_GAIN, effectiveGain(stream.playGain, stream.bus));
            }
        }
    }

    /** Playing OR paused: a paused voice still owns its source and buffer. */
    private static boolean isActive(int source) {
        int state = alGetSourcei(source, AL_SOURCE_STATE);
        return state == AL_PLAYING || state == AL_PAUSED;
    }

    /**
     * Pan/3D positioning, mono sources only (OpenAL plays stereo buffers
     * as-is). Every parameter is re-asserted per play, because voices are
     * recycled.
     */
    private static void applySpatial(int source, boolean mono, float pan, Vec3 position) {
        if (mono && position != null) {
            alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE);
            alSource3f(source, AL_POSITION, position.x(), position.y(), position.z());
        } else if (mono && pan != 0f) {
            // Constant-distance arc in front of the listener: full left/right
            // at ±1, straight ahead at 0 (the stereo-pan idiom in 3D audio).
            alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(source, AL_POSITION,
                    pan, 0f, -(float) Math.sqrt(Math.max(0f, 1f - pan * pan)));
        } else {
            alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(source, AL_POSITION, 0f, 0f, 0f);
        }
    }

    /** Returns the cached device buffer for {@code clip}, uploading it once. */
    private int bufferFor(AudioClip clip) {
        Integer cached = bufferByClip.get(clip);
        if (cached != null) {
            return cached;
        }
        while (bufferByClip.size() >= MAX_CACHED_CLIPS && evictOneUnusedBuffer()) {
            // keep evicting until under the cap or nothing evictable remains
        }
        short[] samples = clip.samples();
        int format = clip.channels() == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
        int buffer = alGenBuffers();
        ShortBuffer pcm = MemoryUtil.memAllocShort(samples.length);
        try {
            pcm.put(samples).flip();
            alBufferData(buffer, format, pcm, clip.sampleRate());
        } finally {
            MemoryUtil.memFree(pcm);
        }
        bufferByClip.put(clip, buffer);
        clipOrder.addLast(clip);
        return buffer;
    }

    /**
     * Deletes the oldest cached device buffer that no active (playing or
     * paused) voice references; evicting an in-use buffer would audibly kill
     * a live sound mid-play. With every cached clip in use the cache simply
     * grows past the cap (a burst-heavy frame, not a leak).
     *
     * @return whether a buffer was evicted
     */
    private boolean evictOneUnusedBuffer() {
        for (AudioClip candidate : clipOrder) {
            Integer buffer = bufferByClip.get(candidate);
            if (buffer == null) {
                continue;
            }
            boolean inUse = false;
            for (Voice voice : voices) {
                if (alGetSourcei(voice.source, AL_BUFFER) == buffer && isActive(voice.source)) {
                    inUse = true;
                    break;
                }
            }
            if (inUse) {
                continue;
            }
            clipOrder.remove(candidate);
            bufferByClip.remove(candidate);
            // Detach from idle sources that still reference it: deleting an
            // attached buffer is an AL error (and would leak it).
            for (Voice voice : voices) {
                if (alGetSourcei(voice.source, AL_BUFFER) == buffer) {
                    alSourceStop(voice.source);
                    alSourcei(voice.source, AL_BUFFER, 0);
                    voice.generation++;
                }
            }
            alDeleteBuffers(buffer);
            return true;
        }
        return false;
    }

    /**
     * Finds a free source, grows the pool, or steals: lowest priority first,
     * oldest (front of the LRU list) among equals, and never a voice of
     * HIGHER priority than the requested sound.
     *
     * @return the voice, or {@code null} when everything is busier and more
     *         important than the request
     */
    private Voice acquireVoice(int priority) {
        for (int i = 0; i < voices.size(); i++) {
            if (!isActive(voices.get(i).source)) {
                return touch(i);
            }
        }
        if (voices.size() < MAX_VOICES) {
            Voice voice = new Voice(alGenSources());
            voices.add(voice);
            return voice;
        }
        // All busy at capacity: steal the oldest among the LOWEST priority
        // class that is still <= the request's.
        int bestIndex = -1;
        int bestPriority = Integer.MAX_VALUE;
        for (int i = 0; i < voices.size(); i++) {
            Voice voice = voices.get(i);
            if (voice.priority <= priority && voice.priority < bestPriority) {
                bestPriority = voice.priority;
                bestIndex = i; // first hit per class = oldest in LRU order
            }
        }
        if (bestIndex < 0) {
            return null;
        }
        alSourceStop(voices.get(bestIndex).source);
        return touch(bestIndex);
    }

    /** Moves the voice at {@code index} to the most-recently-used end. */
    private Voice touch(int index) {
        Voice voice = voices.remove(index);
        voices.add(voice);
        return voice;
    }

    private synchronized void stop(Voice voice, int token) {
        if (initialized && voice.generation == token) {
            alSourceStop(voice.source);
        }
    }

    private synchronized boolean isPlaying(Voice voice, int token) {
        return initialized && voice.generation == token
                && alGetSourcei(voice.source, AL_SOURCE_STATE) == AL_PLAYING;
    }

    private synchronized void setGain(Voice voice, int token, float gain) {
        if (initialized && voice.generation == token) {
            voice.playGain = clamp01(gain);
            alSourcef(voice.source, AL_GAIN, effectiveGain(voice.playGain, voice.bus));
        }
    }

    private synchronized void pause(Voice voice, int token) {
        if (initialized && voice.generation == token
                && alGetSourcei(voice.source, AL_SOURCE_STATE) == AL_PLAYING) {
            alSourcePause(voice.source);
        }
    }

    private synchronized void resume(Voice voice, int token) {
        if (initialized && voice.generation == token
                && alGetSourcei(voice.source, AL_SOURCE_STATE) == AL_PAUSED) {
            alSourcePlay(voice.source);
        }
    }

    private synchronized void setPitch(Voice voice, int token, float pitch) {
        if (initialized && voice.generation == token) {
            alSourcef(voice.source, AL_PITCH, clampPitch(pitch));
        }
    }

    private synchronized void setPan(Voice voice, int token, float pan) {
        if (initialized && voice.generation == token && voice.mono && !voice.positional) {
            applySpatial(voice.source, true, Math.max(-1f, Math.min(1f, pan)), null);
        }
    }

    private synchronized void setPosition(Voice voice, int token, float x, float y, float z) {
        if (initialized && voice.generation == token && voice.positional) {
            applySpatial(voice.source, true, 0f, new Vec3(x, y, z));
        }
    }

    private synchronized double positionSeconds(Voice voice, int token) {
        if (initialized && voice.generation == token && isActive(voice.source)) {
            return alGetSourcef(voice.source, AL_SEC_OFFSET);
        }
        return 0;
    }

    /**
     * A clip is one buffer the device already holds, so there is nothing to discard and nothing to
     * decode: moving the play offset is the whole operation. Past the end the device stops the
     * voice, which is what reaching the end of a clip means.
     */
    private synchronized void seek(Voice voice, int token, long micros) {
        if (initialized && voice.generation == token && isActive(voice.source)) {
            alSourcef(voice.source, AL_SEC_OFFSET, Math.max(0, micros) / 1_000_000f);
        }
    }

    private synchronized boolean canSeek(Voice voice, int token) {
        return initialized && voice.generation == token && isActive(voice.source);
    }

    private static float clamp01(float value) {
        // NaN must not leak into PlayOptions validation or AL_GAIN: treat as 0
        // (the legacy 3-arg play() promised never to throw).
        return Float.isNaN(value) ? 0f : Math.max(0f, Math.min(1f, value));
    }

    private static float clampPitch(float pitch) {
        return Float.isNaN(pitch) ? 1f : Math.max(0.25f, Math.min(4f, pitch));
    }

    // ------------------------------------------------------------- streaming

    @Override
    public synchronized Playback playStream(AudioStreamSource source, PlayOptions options) {
        if (!ensureInitialized()) {
            closeQuietly(source);
            return Playback.NONE;
        }
        // Stopped-but-unreaped streams must not occupy admission slots: a
        // stop-then-play track swap in one event handler has to succeed.
        for (int i = streams.size() - 1; i >= 0; i--) {
            if (streams.get(i).stopRequested) {
                reapStream(i, streams.get(i));
            }
        }
        int channels = source.channels();
        if ((channels != 1 && channels != 2) || streams.size() >= MAX_STREAMS) {
            LOG.log(Level.DEBUG, "stream rejected (channels={0}, active={1})",
                    channels, streams.size());
            closeQuietly(source);
            return Playback.NONE;
        }
        int alSource = -1;
        int[] queuedBuffers = new int[STREAM_BUFFERS];
        int queued = 0;
        try {
            alSource = alGenSources();
            Stream stream = new Stream(alSource, source, channels,
                    source.sampleRate(), options);
            ensureStreamUpload();
            for (int i = 0; i < STREAM_BUFFERS; i++) {
                int frames = readChunk(stream, stream.scratch);
                if (frames <= 0) {
                    break;
                }
                int buffer = alGenBuffers();
                queuedBuffers[queued++] = buffer;
                uploadChunk(stream, buffer, stream.scratch, frames);
                alSourceQueueBuffers(stream.source, buffer);
                stream.framesInBuffer.put(buffer, frames);
            }
            if (queued == 0) {
                alDeleteSources(alSource);
                closeQuietly(source);
                return Playback.NONE; // empty stream
            }
            alSourcef(stream.source, AL_GAIN, effectiveGain(stream.playGain, stream.bus));
            alSourcef(stream.source, AL_PITCH, options.pitch());
            // NEVER AL_LOOPING on a streaming source (it would loop one chunk);
            // looping happens by resetting the decoder at end of data.
            alSourcei(stream.source, AL_LOOPING, AL_FALSE);
            applySpatial(stream.source, channels == 1, options.pan(), options.position());
            alSourcePlay(stream.source);
            streams.add(stream);
            ensureStreamThread();
            return new StreamPlayback(stream);
        } catch (Throwable error) {
            LOG.log(Level.DEBUG, "stream start failed", error);
            // Undo partial AL setup: a failed start must not leak the source
            // or any buffer already generated/queued on it.
            try {
                if (alSource != -1) {
                    alSourceStop(alSource);
                    alSourcei(alSource, AL_BUFFER, 0); // detaches queued buffers
                    for (int i = 0; i < queued; i++) {
                        alDeleteBuffers(queuedBuffers[i]);
                    }
                    alDeleteSources(alSource);
                }
            } catch (Throwable cleanup) {
                LOG.log(Level.DEBUG, "stream start cleanup failed", cleanup);
            }
            closeQuietly(source);
            return Playback.NONE;
        }
    }

    /**
     * Chunk buffers lent to a decode and taken back once it has been uploaded.
     *
     * <p>A refill decodes straight into the borrowed chunk, which then rides the job into the
     * upload. Allocating that chunk per refill was 32&nbsp;KB of garbage roughly six times a
     * second per playing stream, for the whole duration of playback: a track left running is a
     * steady quarter of a megabyte per second of nothing.
     *
     * <p>Touched only by the service thread, which is the one that decodes, uploads and returns.
     *
     * <p>The cap is what stops a moment of many streams from leaving that many buffers resident
     * for the rest of the process; past it a chunk is simply dropped and the next borrow
     * allocates, which is the old behaviour and only for the streams past the cap.
     */
    private static final int CHUNK_POOL_MAX = STREAM_BUFFERS * 4;

    private final java.util.ArrayDeque<short[]> chunkPool = new java.util.ArrayDeque<>();

    /**
     * A chunk buffer, sized for the largest chunk this class decodes. It may be longer than the
     * audio put in it, so every consumer sizes its read by the frame count and the channel count
     * rather than by {@code length}; a borrowed buffer still holding the previous chunk's tail
     * would otherwise queue that tail as audio.
     */
    private short[] borrowChunk() {
        short[] free = chunkPool.poll();
        return free != null ? free : new short[STREAM_CHUNK_FRAMES * 2];
    }

    private void returnChunk(short[] chunk) {
        if (chunk != null && chunkPool.size() < CHUNK_POOL_MAX) {
            chunkPool.add(chunk);
        }
    }

    private void ensureStreamUpload() {
        if (streamUpload == null) {
            streamUpload = MemoryUtil.memAllocShort(STREAM_CHUNK_FRAMES * 2);
        }
    }

    /**
     * Reads one chunk into {@code out}, rewinding once at end-of-data when looping.
     *
     * <p>The destination is the caller's, never a field: a refill lands in the chunk it borrowed,
     * priming and seeks land in the stream's own scratch. The one array this class used to decode
     * everything into was written by the priming caller and by the service thread's unlocked
     * phase at the same time, and two tracks started a moment apart traded samples through it.
     */
    private int readChunk(Stream stream, short[] out) {
        int frames = stream.decoder.readFrames(out, STREAM_CHUNK_FRAMES);
        if (frames <= 0 && stream.loop) {
            // First end-of-data reveals the track length; positionSeconds
            // wraps by it so a looping stream reports in-track time.
            if (stream.loopLengthFrames == 0) {
                stream.loopLengthFrames = stream.framesDelivered;
            }
            stream.decoder.reset();
            frames = stream.decoder.readFrames(out, STREAM_CHUNK_FRAMES);
        }
        if (frames > 0) {
            stream.framesDelivered += frames;
        }
        return frames;
    }

    private void uploadChunk(Stream stream, int buffer, short[] pcm, int frames) {
        streamUpload.clear();
        streamUpload.put(pcm, 0, frames * stream.channels).flip();
        alBufferData(buffer,
                stream.channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16,
                streamUpload, stream.sampleRate);
    }

    private void ensureStreamThread() {
        if (streamThread == null || !streamThread.isAlive()) {
            streamThread = new Thread(this::streamLoop, "limn-audio-stream");
            streamThread.setDaemon(true);
            streamThread.start();
        }
    }

    /** A stream whose queue a seek emptied, waiting for the service thread to refill it. */
    private static final class SeekJob {
        final Stream stream;
        final long micros;
        final long serial;

        SeekJob(Stream stream, long micros, long serial) {
            this.stream = stream;
            this.micros = micros;
            this.serial = serial;
        }
    }

    /** A processed buffer waiting for its refill decode (between lock phases). */
    private static final class RefillJob {
        final Stream stream;
        final int buffer;
        /**
         * The stream's seek serial when this job was taken. A seek can land while this job's decode
         * is in flight, and what it decoded is then audio from a position the caller has left,
         * which must not be queued behind the audio the seek primed.
         */
        final long serial;
        short[] pcm;   // decoded outside the monitor
        int frames;

        RefillJob(Stream stream, int buffer) {
            this.stream = stream;
            this.buffer = buffer;
            this.serial = stream.seekSerial;
        }
    }

    private void streamLoop() {
        List<RefillJob> jobs = new ArrayList<>();
        List<SeekJob> seeking = new ArrayList<>();
        while (true) {
            jobs.clear();
            seeking.clear();
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (streams.isEmpty()) {
                    streamThread = null; // idle: stop ticking; restarted on next playStream
                    return;
                }
                for (Stream stream : streams) {
                    if (stream.seekPending && !stream.stopRequested && !stream.finished) {
                        seeking.add(new SeekJob(stream, stream.seekMicros, stream.seekSerial));
                    }
                }
                collectRefillJobs(jobs); // phase A: unqueue + bookkeeping (AL, locked)
            }
            applySeeks(seeking);
            // Phase B, UNLOCKED: decoding is file I/O + codec work; a slow
            // disk must not stall play()/mixer calls on the UI thread. Safe
            // without the lock because everything it touches is this thread's
            // alone: each decoder after its start (stop() merely flags; reaping
            // happens back here, or in close() after this thread has exited),
            // the chunk pool, and the borrowed chunk the decode lands in. A
            // caller priming a new track meanwhile writes its own stream's
            // scratch, not anything on this path.
            for (RefillJob job : jobs) {
                try {
                    job.pcm = borrowChunk();
                    int frames = readChunk(job.stream, job.pcm);
                    if (frames > 0) {
                        job.frames = frames;
                    }
                } catch (Throwable error) {
                    LOG.log(Level.DEBUG, "stream decode failed", error);
                    job.stream.stopRequested = true;
                }
            }
            synchronized (this) {
                if (closed) {
                    return;
                }
                applyRefills(jobs);      // phase C: upload + queue (AL, locked)
                serviceStreamStates();   // underruns, drains, stop requests
            }
            // After the upload, and after every path that skipped it: a job whose audio was
            // dropped (stream stopped, seeked past) still borrowed a buffer.
            for (RefillJob job : jobs) {
                returnChunk(job.pcm);
                job.pcm = null;
            }
            try {
                Thread.sleep(30);
            } catch (InterruptedException interrupted) {
                return;
            }
        }
    }

    /**
     * The decoder half of {@link StreamPlayback#seek}: reposition and refill. The AL half already
     * happened under the caller's monitor (the queue is empty and the source is stopped), so what
     * is left is the part that must not run on any thread but this one, because a decoder is
     * touched by the service thread alone after the start.
     *
     * <p>Decoding is unlocked for the reason phase B is: a seek in a container is file I/O and codec
     * work, and holding the monitor across it would stall every mixer call on the UI thread.
     */
    private void applySeeks(List<SeekJob> seeking) {
        for (SeekJob job : seeking) {
            Stream stream = job.stream;
            int[] frames = new int[STREAM_BUFFERS];
            short[][] pcm = new short[STREAM_BUFFERS][];
            int chunks = 0;
            try {
                stream.decoder.seek(job.micros);
                for (; chunks < STREAM_BUFFERS; chunks++) {
                    int read = readChunk(stream, stream.scratch);
                    if (read <= 0) {
                        break; // seeked past the end of the track; it drains and is reaped
                    }
                    // Not pooled, unlike the refill path: a seek is a user gesture a few times
                    // a session, where a refill is six times a second for as long as the track
                    // plays. Borrowing here would mean returning across three `continue` paths
                    // in a method that decodes unlocked and uploads locked, which is a lot of
                    // exposure in this file to save an allocation nobody can measure.
                    pcm[chunks] = new short[read * stream.channels];
                    System.arraycopy(stream.scratch, 0, pcm[chunks], 0, pcm[chunks].length);
                    frames[chunks] = read;
                }
            } catch (Throwable error) {
                LOG.log(Level.DEBUG, "stream seek failed", error);
                stream.stopRequested = true;
                continue;
            }
            synchronized (this) {
                if (closed || stream.finished || stream.stopRequested) {
                    continue;
                }
                if (stream.seekSerial != job.serial) {
                    // Seeked again while this one was decoding. What was read belongs to a position
                    // the caller has already left, so it is dropped and the newer request is served
                    // by the next pass, which is why the pending flag is not cleared here.
                    continue;
                }
                stream.seekPending = false;
                try {
                    for (int i = 0; i < chunks; i++) {
                        int buffer = alGenBuffers();
                        streamUpload.clear();
                        streamUpload.put(pcm[i], 0, frames[i] * stream.channels).flip();
                        alBufferData(buffer,
                                stream.channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16,
                                streamUpload, stream.sampleRate);
                        alSourceQueueBuffers(stream.source, buffer);
                        stream.framesInBuffer.put(buffer, frames[i]);
                    }
                    if (chunks > 0 && !stream.paused) {
                        alSourcePlay(stream.source);
                    }
                } catch (Throwable error) {
                    LOG.log(Level.DEBUG, "stream seek refill failed", error);
                    stream.stopRequested = true;
                }
            }
        }
    }

    /** Phase A: pull processed buffers off each live stream (monitor held). */
    private void collectRefillJobs(List<RefillJob> jobs) {
        for (Stream stream : streams) {
            if (stream.stopRequested || stream.finished) {
                continue;
            }
            try {
                int processed = alGetSourcei(stream.source, AL_BUFFERS_PROCESSED);
                for (int p = 0; p < processed; p++) {
                    int buffer = alSourceUnqueueBuffers(stream.source);
                    Integer played = stream.framesInBuffer.remove(buffer);
                    stream.completedFrames += played == null ? 0 : played;
                    jobs.add(new RefillJob(stream, buffer));
                }
            } catch (Throwable error) {
                LOG.log(Level.DEBUG, "stream service failed", error);
                stream.stopRequested = true;
            }
        }
    }

    /** Phase C: upload decoded chunks and requeue (monitor held). */
    private void applyRefills(List<RefillJob> jobs) {
        for (RefillJob job : jobs) {
            Stream stream = job.stream;
            try {
                if (stream.finished || stream.stopRequested || job.frames <= 0
                        || stream.seekSerial != job.serial) {
                    // Stream over, end of data, or seeked while this decode was in flight, in
                    // which case what it holds is audio from before the seek, and queueing it
                    // would play the position the caller just left, after the one they asked for.
                    alDeleteBuffers(job.buffer);
                    continue;
                }
                streamUpload.clear();
                streamUpload.put(job.pcm, 0, job.frames * stream.channels).flip();
                alBufferData(job.buffer,
                        stream.channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16,
                        streamUpload, stream.sampleRate);
                alSourceQueueBuffers(stream.source, job.buffer);
                stream.framesInBuffer.put(job.buffer, job.frames);
            } catch (Throwable error) {
                LOG.log(Level.DEBUG, "stream refill failed", error);
                stream.stopRequested = true;
            }
        }
    }

    /** Underruns, natural ends and stop requests (monitor held). */
    private void serviceStreamStates() {
        for (int i = streams.size() - 1; i >= 0; i--) {
            Stream stream = streams.get(i);
            if (stream.stopRequested) {
                reapStream(i, stream);
                continue;
            }
            if (stream.seekPending) {
                // A seek leaves exactly the shape a drained stream has (no buffers queued, source
                // stopped), and the refill is a service pass away. Without this the first seek on
                // any track silently ends it, which is the one thing here worth a guard of its own.
                continue;
            }
            try {
                int queued = alGetSourcei(stream.source, AL_BUFFERS_QUEUED);
                int state = alGetSourcei(stream.source, AL_SOURCE_STATE);
                if (queued == 0 && state != AL_PLAYING) {
                    reapStream(i, stream); // fully drained
                } else if (!stream.paused && state != AL_PLAYING && state != AL_PAUSED) {
                    alSourcePlay(stream.source); // service-lag underrun: restart
                }
            } catch (Throwable error) {
                LOG.log(Level.DEBUG, "stream state check failed", error);
                reapStream(i, stream);
            }
        }
    }

    /** Stops, frees and closes stream {@code i} (idempotent per stream). */
    private void reapStream(int index, Stream stream) {
        reapStream(index, stream, true);
    }

    /**
     * {@link #reapStream(int, Stream)}, with the decoder left open when the service thread may
     * still be inside it: the AL half is safe to take away under the monitor (that thread never
     * touches AL without it), the decoder half is not.
     */
    private void reapStream(int index, Stream stream, boolean closeDecoder) {
        stream.finished = true;
        streams.remove(index);
        try {
            alSourceStop(stream.source);
            int queued = alGetSourcei(stream.source, AL_BUFFERS_QUEUED);
            for (int q = 0; q < queued; q++) {
                alDeleteBuffers(alSourceUnqueueBuffers(stream.source));
            }
            alDeleteSources(stream.source);
        } catch (Throwable error) {
            LOG.log(Level.DEBUG, "stream cleanup failed", error);
        }
        if (closeDecoder) {
            closeQuietly(stream.decoder);
        } else {
            LOG.log(Level.WARNING, "stream decoder left open: the service thread is still "
                    + "decoding from it at shutdown");
        }
    }

    private static void closeQuietly(AudioStreamSource source) {
        try {
            source.close();
        } catch (Throwable error) {
            LOG.log(Level.DEBUG, "stream close failed", error);
        }
    }

    // --------------------------------------------------------------- shutdown

    /**
     * Shutdown in three steps whose order is the point. First the monitor is taken only long
     * enough to mark the engine closed and take the service thread's handle; then that thread is
     * interrupted and joined, outside the monitor because it needs the monitor to observe the
     * flag and leave; and only once it has left are the decoders closed and the device torn
     * down. Closing the decoders first, under the monitor, looked orderly and was a use after
     * free: the service thread decodes with the monitor released, and a stream's decoder being
     * closed under it (a Vorbis handle freed, its encoded bytes returned) while it was inside a
     * native read was a crash on exit with a track playing.
     *
     * <p>A thread that has not left by the deadline keeps its decoders: they leak, which is
     * logged, rather than being pulled from under a read that is still running.
     */
    @Override
    public void close() {
        Thread thread;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            thread = streamThread;
            streamThread = null;
        }
        boolean serviceThreadGone = true;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            serviceThreadGone = !thread.isAlive();
        }
        synchronized (this) {
            for (int i = streams.size() - 1; i >= 0; i--) {
                reapStream(i, streams.get(i), serviceThreadGone);
            }
            for (Voice voice : voices) {
                alDeleteSources(voice.source);
            }
            voices.clear();
            for (int buffer : bufferByClip.values()) {
                alDeleteBuffers(buffer);
            }
            bufferByClip.clear();
            clipOrder.clear();
            if (streamUpload != null) {
                MemoryUtil.memFree(streamUpload);
                streamUpload = null;
            }
            if (context != 0L) {
                alcMakeContextCurrent(0L);
                alcDestroyContext(context);
                context = 0L;
            }
            if (device != 0L) {
                alcCloseDevice(device);
                device = 0L;
            }
            initialized = false;
        }
    }

    // ----------------------------------------------------------------- types

    /** A reusable OpenAL source; {@code generation} invalidates stale handles on reuse. */
    private static final class Voice {
        final int source;
        int generation;
        float playGain = 1f;
        AudioBus bus;
        int priority;
        boolean mono;
        boolean positional; // started via PlayOptions.at: gates setPosition/setPan

        Voice(int source) {
            this.source = source;
        }
    }

    /** A dedicated streaming source with its queued-chunk bookkeeping. */
    private static final class Stream {
        final int source;
        final AudioStreamSource decoder;
        final int channels;
        final int sampleRate;
        final boolean loop;
        final boolean positional;
        final Map<Integer, Integer> framesInBuffer = new HashMap<>();
        /**
         * Where this stream's priming and seek decodes land. Priming runs on the caller's thread
         * before the stream is in {@link #streams}, seeks on the service thread after; nothing
         * else reads it, so the two never meet. Refills do not use it: they decode into the chunk
         * they borrowed, on the service thread alone.
         */
        final short[] scratch = new short[STREAM_CHUNK_FRAMES * 2];
        float playGain;
        AudioBus bus;
        long completedFrames;   // frames fully played (dequeued)
        long framesDelivered;   // frames handed out by the decoder (readChunk)
        long loopLengthFrames;  // track length, learned at the first loop rewind
        boolean paused;
        boolean stopRequested;
        boolean finished;
        /** A seek has emptied the queue and the service thread has not refilled it yet. */
        boolean seekPending;
        long seekMicros;
        /** Bumped per seek, so a refill decoded for a target the caller has left is discarded. */
        long seekSerial;

        Stream(int source, AudioStreamSource decoder, int channels, int sampleRate,
               PlayOptions options) {
            this.source = source;
            this.decoder = decoder;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.loop = options.loop();
            this.positional = options.position() != null && channels == 1;
            this.playGain = options.gain();
            this.bus = options.bus();
        }
    }

    /** A {@link Playback} bound to a voice at a specific generation. */
    private final class OpenAlPlayback implements Playback {
        private final Voice voice;
        private final int token;

        OpenAlPlayback(Voice voice, int token) {
            this.voice = voice;
            this.token = token;
        }

        @Override
        public void stop() {
            OpenAlAudio.this.stop(voice, token);
        }

        @Override
        public boolean isPlaying() {
            return OpenAlAudio.this.isPlaying(voice, token);
        }

        @Override
        public void setGain(float gain) {
            OpenAlAudio.this.setGain(voice, token, gain);
        }

        @Override
        public void pause() {
            OpenAlAudio.this.pause(voice, token);
        }

        @Override
        public void resume() {
            OpenAlAudio.this.resume(voice, token);
        }

        @Override
        public void setPitch(float pitch) {
            OpenAlAudio.this.setPitch(voice, token, pitch);
        }

        @Override
        public void setPan(float pan) {
            OpenAlAudio.this.setPan(voice, token, pan);
        }

        @Override
        public void setPosition(float x, float y, float z) {
            OpenAlAudio.this.setPosition(voice, token, x, y, z);
        }

        @Override
        public double positionSeconds() {
            return OpenAlAudio.this.positionSeconds(voice, token);
        }

        @Override
        public boolean canSeek() {
            return OpenAlAudio.this.canSeek(voice, token);
        }

        @Override
        public void seek(long micros) {
            OpenAlAudio.this.seek(voice, token, micros);
        }
    }

    /** A {@link Playback} for a streaming source (cleanup on the service thread). */
    private final class StreamPlayback implements Playback {
        private final Stream stream;

        StreamPlayback(Stream stream) {
            this.stream = stream;
        }

        @Override
        public void stop() {
            synchronized (OpenAlAudio.this) {
                if (!stream.finished && initialized) {
                    stream.stopRequested = true; // reaped by the service thread
                    alSourceStop(stream.source); // silence immediately
                }
            }
        }

        @Override
        public boolean isPlaying() {
            synchronized (OpenAlAudio.this) {
                return !stream.finished && initialized
                        && alGetSourcei(stream.source, AL_SOURCE_STATE) == AL_PLAYING;
            }
        }

        @Override
        public void setGain(float gain) {
            synchronized (OpenAlAudio.this) {
                if (!stream.finished && initialized) {
                    stream.playGain = clamp01(gain);
                    alSourcef(stream.source, AL_GAIN,
                            effectiveGain(stream.playGain, stream.bus));
                }
            }
        }

        @Override
        public void pause() {
            synchronized (OpenAlAudio.this) {
                if (!stream.finished && initialized) {
                    // Sticky: even mid-underrun (source momentarily STOPPED)
                    // the flag holds, so the service loop won't restart what
                    // the user just paused.
                    stream.paused = true;
                    if (alGetSourcei(stream.source, AL_SOURCE_STATE) == AL_PLAYING) {
                        alSourcePause(stream.source);
                    }
                }
            }
        }

        @Override
        public void resume() {
            synchronized (OpenAlAudio.this) {
                if (!stream.finished && initialized && stream.paused) {
                    stream.paused = false;
                    if (alGetSourcei(stream.source, AL_BUFFERS_QUEUED) > 0) {
                        alSourcePlay(stream.source);
                    } // else: the service loop restarts it after the refill
                }
            }
        }

        @Override
        public void setPitch(float pitch) {
            synchronized (OpenAlAudio.this) {
                if (!stream.finished && initialized) {
                    alSourcef(stream.source, AL_PITCH, clampPitch(pitch));
                }
            }
        }

        @Override
        public void setPan(float pan) {
            synchronized (OpenAlAudio.this) {
                if (!stream.finished && initialized && stream.channels == 1
                        && !stream.positional) {
                    applySpatial(stream.source, true,
                            Math.max(-1f, Math.min(1f, pan)), null);
                }
            }
        }

        @Override
        public void setPosition(float x, float y, float z) {
            synchronized (OpenAlAudio.this) {
                if (!stream.finished && initialized && stream.positional) {
                    applySpatial(stream.source, true, 0f, new Vec3(x, y, z));
                }
            }
        }

        @Override
        public double positionSeconds() {
            synchronized (OpenAlAudio.this) {
                if (stream.finished || !initialized) {
                    return 0;
                }
                double frames = stream.completedFrames
                        + alGetSourcef(stream.source, AL_SEC_OFFSET) * stream.sampleRate;
                if (stream.loopLengthFrames > 0) {
                    frames %= stream.loopLengthFrames; // looping: report in-track time
                }
                return frames / stream.sampleRate;
            }
        }

        @Override
        public boolean canSeek() {
            synchronized (OpenAlAudio.this) {
                return !stream.finished && initialized && stream.decoder.canSeek();
            }
        }

        @Override
        public void seek(long micros) {
            synchronized (OpenAlAudio.this) {
                if (stream.finished || !initialized || !stream.decoder.canSeek()) {
                    return;
                }
                long target = Math.max(0, micros);
                // The AL side, now: silence what is queued and rebase the position, so that a
                // caller re-anchoring a video clock against this reads the target rather than the
                // position being left; a reading that caught up a moment later would be scored as
                // the track having run away and would cost the video its master.
                alSourceStop(stream.source);
                int queued = alGetSourcei(stream.source, AL_BUFFERS_QUEUED);
                for (int q = 0; q < queued; q++) {
                    alDeleteBuffers(alSourceUnqueueBuffers(stream.source));
                }
                stream.framesInBuffer.clear();
                long frames = Math.round(target / 1_000_000.0 * stream.sampleRate);
                stream.completedFrames = frames;
                stream.framesDelivered = frames;
                stream.seekMicros = target;
                stream.seekSerial++;
                // The decoder side is the service thread's, because it is the only thread allowed
                // to touch a decoder after the start; until it gets there this source is stopped
                // with an empty queue, which is why serviceStreamStates must not reap it.
                stream.seekPending = true;
            }
        }
    }
}
