package limn.video.ffmpeg;

import limn.sound.AudioStreamSource;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A container that can say what is inside it: several audio tracks, listed, and one of them
 * selected.
 *
 * <p>Every clip here is written by the test, because a clip this suite can produce is not one it
 * carries, and the writer gives each track a tone of its own for the reason this file exists:
 * "am I hearing the track I asked for" is otherwise a question about levels, and a wrong index, a
 * wrong stream filter and a downmix all look alike in a level. Here they are three different
 * frequencies.
 */
class AudioTrackListTest {

    @TempDir
    Path directory;

    /** English stereo, French mono, and one that states no language at all. */
    private static final List<FfmpegMedia.ClipAudioTrack> THREE = List.of(
            new FfmpegMedia.ClipAudioTrack(2, "eng"),
            new FfmpegMedia.ClipAudioTrack(1, "fra"),
            new FfmpegMedia.ClipAudioTrack(2, null));

    private Path threeTrackClip(int frames) throws IOException {
        return FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MJPEG, 160, 120, frames, THREE);
    }

    // ------------------------------------------------------------------ what the container says

    @Test
    void everyAudioTrackIsListedWithWhatTheContainerSaysAboutIt() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(threeTrackClip(20))) {
            List<FfmpegMedia.AudioTrack> tracks = media.audioTracks();
            assertEquals(3, tracks.size(), "three audio streams were written and three are listed");

            for (int index = 0; index < tracks.size(); index++) {
                FfmpegMedia.AudioTrack track = tracks.get(index);
                assertEquals(index, track.index(), "the index is the position in this list");
                assertEquals("aac", track.codec());
                assertEquals(44_100, track.sampleRate());
                assertTrue(track.decodable(), "this build has an AAC decoder");
                // The container's own index, which is not the list position: the video stream is 0.
                assertEquals(index + 1, track.streamIndex());
            }

            assertEquals(2, tracks.get(0).sourceChannels());
            assertEquals(2, tracks.get(0).channels());
            assertEquals(1, tracks.get(1).sourceChannels());
            assertEquals(1, tracks.get(1).channels(), "a mono track is not folded up to stereo");
        }
    }

    @Test
    void aTrackWithNoLanguageReportsNoneRatherThanABlank() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(threeTrackClip(20))) {
            assertEquals("eng", media.audioTracks().get(0).language());
            assertEquals("fra", media.audioTracks().get(1).language());
            // Not "", which a user interface would render as an empty cell in a language column,
            // and not "und", which it would render as a language nobody speaks.
            assertNull(media.audioTracks().get(2).language(),
                    "a track whose language the file does not state reports none");
        }
    }

    /**
     * What a container actually records for a track nobody gave a language to, which is the fact
     * the mapping above rests on: the MP4 muxer writes the ISO 639-2 undetermined code rather than
     * leaving the tag out, so "absent" and "und" both have to mean no language.
     */
    @Test
    void theUndeterminedCodeIsNotALanguage() {
        assertNull(FfmpegMedia.languageOf("und"));
        assertNull(FfmpegMedia.languageOf(null));
        assertNull(FfmpegMedia.languageOf(""));
        assertEquals("eng", FfmpegMedia.languageOf("eng"));
    }

    @Test
    void oneTrackIsTheDefaultAndItIsTheOneAudioHandsOver() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(threeTrackClip(20))) {
            long defaults = media.audioTracks().stream()
                    .filter(FfmpegMedia.AudioTrack::isDefault).count();
            assertEquals(1, defaults, "exactly one track is the default");
            assertEquals(0, media.selectedAudioTrack(),
                    "and it is the one open at the moment the container opens");
            assertTrue(media.audioTracks().get(0).isDefault());

            // audio() has always meant "the default one", and it keeps meaning it: the same object
            // both times, and the same object audio(0) gives.
            AudioStreamSource first = media.audio();
            assertSame(first, media.audio());
            assertSame(first, media.audio(0));
        }
    }

    @Test
    void aSingleTrackContainerStillListsItsOneTrack() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(FfmpegTests.clip(directory, 160, 120, 16, 2))) {
            assertEquals(1, media.audioTracks().size());
            assertTrue(media.audioTracks().get(0).isDefault());
            assertEquals(0, media.selectedAudioTrack());
        }
        try (FfmpegMedia media = FfmpegMedia.open(FfmpegTests.clip(directory, 160, 120, 16, 0))) {
            assertEquals(List.of(), media.audioTracks(), "a silent film lists no tracks");
            assertEquals(-1, media.selectedAudioTrack());
            assertNull(media.audio());
        }
    }

    // ------------------------------------------------------------------ hearing the right one

    @Test
    void theSelectedTrackIsTheOneThatIsHeard() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(threeTrackClip(60))) {
            // The default first: 440 Hz, which is what this clip's track 0 carries.
            assertDominantTone(media.audio(), 0);

            // Then the French one, a third of the way up: if the demultiplexer were still filtering
            // on the old stream, or the decoder still the old one, this would be 440 Hz again.
            assertDominantTone(media.audio(1), 1);

            // And back, which needs the container to move nothing and the codec to be reopened.
            assertDominantTone(media.audio(2), 2);
            assertDominantTone(media.audio(0), 0);
        }
    }

    /**
     * Reads a second of {@code audio} and asserts that what is in it is the tone the clip writer
     * gave {@code track} and, as the control, that the tones of the other two tracks are not.
     */
    private void assertDominantTone(AudioStreamSource audio, int track) {
        assertNotNull(audio);
        int channels = audio.channels();
        short[] out = new short[8192 * channels];
        int frames = 0;
        // Several refills: the first may be short, and one AAC frame is 1024 samples.
        for (int attempt = 0; attempt < 16 && frames < 4096; attempt++) {
            int read = audio.readFrames(out, out.length / channels);
            if (read <= 0) {
                break;
            }
            frames = read;
        }
        assertTrue(frames > 1024, "track " + track + " delivered " + frames + " frames");

        double mine = FfmpegTests.energyAt(out, frames, channels, 0, audio.sampleRate(),
                FfmpegTests.toneOf(track, 0));
        assertTrue(mine > 0.01,
                "track " + track + " should carry " + FfmpegTests.toneOf(track, 0) + " Hz, and the "
                        + "energy there is " + mine);
        for (int other = 0; other < 3; other++) {
            if (other == track) {
                continue;
            }
            double theirs = FfmpegTests.energyAt(out, frames, channels, 0, audio.sampleRate(),
                    FfmpegTests.toneOf(other, 0));
            assertTrue(mine > theirs * 8,
                    "what is playing should be track " + track + "'s tone rather than track "
                            + other + "'s, and the two energies are " + mine + " and " + theirs);
        }
    }

    // ------------------------------------------------------------------ what a selection costs

    @Test
    void selectingAnotherTrackEndsTheOneThatWasOpen() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(threeTrackClip(60))) {
            AudioStreamSource first = media.audio();
            short[] out = new short[2048 * 2];
            assertTrue(first.readFrames(out, 2048) > 0, "the first track is delivering");

            AudioStreamSource second = media.audio(1);
            assertNotSame(first, second);
            assertEquals(1, media.selectedAudioTrack());

            // The engine holds a source it was given and reads until the track ends. A superseded
            // track ENDS (it does not throw and does not keep delivering the old language over
            // the new one), which is what makes the engine let go of it on its own.
            assertEquals(0, first.readFrames(out, 2048),
                    "a superseded track reports the end, which is what the engine acts on");
            assertTrue(second.readFrames(out, 2048) > 0, "and the new one delivers");
        }
    }

    /**
     * The quiet one. The audio engine closes every source it is given, so the close of a track that
     * was replaced arrives <em>after</em> the replacement, and a release that ignored which track
     * it came from would tell the demultiplexer that nobody is reading the track somebody is
     * listening to. Nothing throws, and a reader that is alone still gets its packets, because the
     * track it asked for is pulled for it directly. What is lost is everything the OTHER consumer
     * demultiplexes past: an unclaimed track's packets are freed as they are met, so the sound
     * disappears exactly in proportion to how far ahead the pictures are, which is silence, in a
     * player, where the pictures always run.
     *
     * <p>So the pictures are run to the end here, deliberately, between the close and the read.
     * Without that this passes with the guard removed.
     */
    @Test
    void aSupersededTrackClosingDoesNotSilenceTheNewOne() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(threeTrackClip(90))) {
            AudioStreamSource first = media.audio();
            short[] out = new short[2048 * 2];
            assertTrue(first.readFrames(out, 2048) > 0);

            AudioStreamSource second = media.audio(1);
            first.close(); // the engine, finishing with what it was told had ended

            VideoStreamSource video = media.video();
            int pictures = 0;
            while (RoundTripTest.readNext(video) == VideoStreamSource.Read.FRAME) {
                video.frame().release();
                pictures++;
            }
            assertEquals(90, pictures);

            long total = 0;
            for (int attempt = 0; attempt < 400; attempt++) {
                int read = second.readFrames(out, 2048);
                if (read <= 0) {
                    break;
                }
                total += read;
            }
            assertTrue(total > 100_000,
                    "the selected track was queued while the pictures ran past it, and delivered "
                            + total + " frames");
            assertEquals(0L, media.droppedPackets()[1],
                    "and none of it was dropped: the queue's bound is far above a clip this long");
        }
    }

    /**
     * A track nobody selected has its packets freed as they are demultiplexed, never queued, which
     * is the difference between a film's other languages costing nothing and costing memory for the
     * length of the film.
     *
     * <p>The clip is six seconds for that reason and not by taste. One AAC track of it is about 260
     * packets, comfortably inside the queue's bound of 512; three of them are about 780, which is
     * over it, so a shim that queued every audio stream rather than the selected one would show up
     * as dropped packets and a queue at its bound, and one that queues only the selected one shows
     * up as neither.
     */
    @Test
    void aTrackNobodySelectedIsNeverQueued() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(threeTrackClip(180))) {
            VideoStreamSource video = media.video();
            int pictures = 0;
            while (RoundTripTest.readNext(video) == VideoStreamSource.Read.FRAME) {
                video.frame().release();
                pictures++;
            }
            assertEquals(180, pictures);

            long[] queued = media.queuedPackets();
            assertTrue(queued[1] > 0 && queued[1] < 400,
                    "one track's six seconds are waiting, not three tracks': " + queued[1]
                            + " packets are queued");
            assertEquals(0L, media.droppedPackets()[1],
                    "and nothing was dropped, which three tracks in one queue could not manage");
            assertEquals(0L, media.droppedPackets()[0], "the pictures were never dropped");
        }
    }

    @Test
    void releasingTheSelectedTrackStopsQueueingItAndTheOthersStillCostNothing() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(threeTrackClip(120))) {
            media.audio(1).close();
            VideoStreamSource video = media.video();
            int pictures = 0;
            while (RoundTripTest.readNext(video) == VideoStreamSource.Read.FRAME) {
                video.frame().release();
                pictures++;
            }
            assertEquals(120, pictures);
            assertEquals(0L, media.queuedPackets()[1],
                    "with no consumer, the selected track's packets are freed as they are met");
            assertEquals(0L, media.droppedPackets()[1],
                    "and a track that is never queued is never dropped either");
        }
    }

    // ------------------------------------------------------------------ seeking across a change

    private static final long FRAME = 1_000_000L / 30;

    @Test
    void twoTracksStillShareOnePlacementWhenBothAskForIt() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(FfmpegTests.clip(directory,
                FfmpegMedia.ClipCodec.MPEG4, 160, 120, 90, THREE))) {
            long before = media.containerSeeks();
            media.video().seek(40 * FRAME, VideoStreamSource.SeekMode.EXACT);
            media.audio().seek(40 * FRAME);
            assertEquals(before + 1, media.containerSeeks(),
                    "listing tracks changed nothing here: one placement, two consumers, one move");
        }
    }

    /**
     * One container position serves two consumers, and the shim remembers which of them has taken
     * it up so that a target both ask for costs one move rather than two. What makes that safe for
     * the consumer that has not asked yet is that its packets from there were <em>queued</em> while
     * the other one read forward.
     *
     * <p>A newly selected track has no such queue: while the pictures were running past the target,
     * this track was not the selected one and its packets were freed as they were met. So a
     * selection has to invalidate that bookkeeping, or the new track takes up a placement whose
     * packets are gone and plays from wherever the demultiplexer has since reached: a language
     * switch that lands somewhere else in the film, with nothing reporting a fault.
     */
    @Test
    void aTrackChangeMakesTheNextAudioSeekARealMove() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(FfmpegTests.clip(directory,
                FfmpegMedia.ClipCodec.MPEG4, 160, 120, 90, THREE))) {
            VideoStreamSource video = media.video();
            long target = 40 * FRAME;
            video.seek(target, VideoStreamSource.SeekMode.EXACT);

            // The pictures run on from there, which is what carries the demultiplexer past the
            // target, and what frees every packet of the track that is about to be selected.
            for (int i = 0; i < 20; i++) {
                assertSame(VideoStreamSource.Read.FRAME, video.readFrame());
                video.frame().release();
            }

            long placed = media.containerSeeks();
            AudioStreamSource other = media.audio(1);
            other.seek(target);
            assertEquals(placed + 1, media.containerSeeks(),
                    "the newly selected track asked for a position the pictures had taken up, and "
                            + "its own packets from there were never kept, so the container really "
                            + "moves rather than reporting itself already there");

            short[] out = new short[4096];
            assertTrue(other.readFrames(out, out.length / other.channels()) > 0,
                    "and it delivers from there rather than reporting an end");
        }
    }

    @Test
    void selectingATrackDoesNotDisturbThePictures() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(threeTrackClip(60))) {
            VideoStreamSource video = media.video();
            int pictures = 0;
            while (RoundTripTest.readNext(video) == VideoStreamSource.Read.FRAME) {
                video.frame().release();
                pictures++;
                if (pictures == 20) {
                    media.audio(1); // mid-decode, the way a viewer picks a language
                }
            }
            assertEquals(60, pictures, "every picture still arrives across a track change");
            assertEquals(0L, media.droppedPackets()[0]);
        }
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    void askingForATrackThatIsNotThereSaysSo() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(threeTrackClip(16))) {
            assertThrows(IndexOutOfBoundsException.class, () -> media.audio(3));
            assertThrows(IndexOutOfBoundsException.class, () -> media.audio(-1));
        }
    }

    @Test
    void aContainerOpenedWithoutAudioListsItsTracksAndOpensNone() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = threeTrackClip(16);
        try (FfmpegMedia media = FfmpegMedia.open(clip, false, FfmpegMedia.DEFAULT_SLOTS)) {
            // The tracks are a fact about the file, so they are listed either way, but nothing was
            // opened, and quietly opening one now would undo the caller's own decision.
            assertEquals(3, media.audioTracks().size());
            assertEquals(-1, media.selectedAudioTrack());
            assertNull(media.audio());
            assertFalse(media.hasAudio());
            assertThrows(IllegalStateException.class, () -> media.audio(1));
        }
    }

    @Test
    void selectingOnAClosedContainerSaysSoRatherThanReachingAFreedHandle() throws IOException {
        FfmpegTests.requireWriter();
        FfmpegMedia media = FfmpegMedia.open(threeTrackClip(16));
        media.close();
        assertThrows(FfmpegException.class, () -> media.audio(1));
    }

    // ------------------------------------------------------------------ real files

    /**
     * The tier the written clips cannot cover: a real film, with the tracks a real muxer wrote and
     * the language tags a real release carries. Point {@code -Dlimn.video.test.clips} at a
     * directory; skipped, loudly, when it is not set.
     *
     * <p>It is also the only place a Matroska file is read here: the linked build has a Matroska
     * demuxer and no Matroska muxer, so this repository cannot write one to check its tags against.
     */
    @Test
    void everyTrackOfEveryRealFileCanBeListedAndSelected() throws IOException {
        FfmpegTests.requireLibrary();
        List<Path> files = FfmpegTests.realClips("to list and select the tracks of real films here");

        int multiTrackFiles = 0;
        for (Path file : files) {
            FfmpegMedia opened;
            try {
                opened = FfmpegMedia.open(file);
            } catch (RuntimeException notPlayable) {
                continue; // not a container this build reads; CodecBreadthTest reports on that
            }
            try (FfmpegMedia media = opened) {
                List<FfmpegMedia.AudioTrack> tracks = media.audioTracks();
                if (tracks.size() > 1) {
                    multiTrackFiles++;
                }
                for (FfmpegMedia.AudioTrack track : tracks) {
                    assertNotNull(track.codec());
                    assertTrue(track.language() == null || !track.language().isBlank(),
                            "a language is a tag or it is nothing: " + track);
                    assertTrue(track.channels() == 1 || track.channels() == 2,
                            "what a track delivers is what the engine admits: " + track);
                    if (!track.decodable()) {
                        assertThrows(FfmpegException.class, () -> media.audio(track.index()));
                        continue;
                    }
                    AudioStreamSource audio = media.audio(track.index());
                    assertEquals(track.channels(), audio.channels());
                    assertEquals(track.sampleRate(), audio.sampleRate());
                    short[] out = new short[4096 * audio.channels()];
                    assertTrue(audio.readFrames(out, 4096) > 0,
                            "track " + track.index() + " of " + file.getFileName()
                                    + " delivers samples");
                }
            }
        }
        System.out.println("files with more than one audio track: " + multiTrackFiles
                + " of " + files.size());
    }
}
