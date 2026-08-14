package limn.video.ffmpeg;

import limn.video.VideoStreamSource;
import limn.video.VideoStreamSource.SeekMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The subtitle SPI over a real container: what a file's tracks are, what selecting one costs, what
 * a cue's text is once the markup is off it, and what a seek does to the cues already held.
 *
 * <p>Every test writes the clip it reads (no media and no subtitle file is committed), and every
 * one skips cleanly where the native is absent, which is the normal case.
 */
class SubtitleTest {

    @TempDir
    Path directory;

    private Path clip(int frames, List<String> subtitles) throws IOException {
        return FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MJPEG, 160, 120, frames,
                List.of(new FfmpegMedia.ClipAudioTrack(2, "eng")), subtitles);
    }

    // ------------------------------------------------------------------ the listing

    @Test
    void everySubtitleTrackIsListedWithItsLanguage() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(40, List.of("eng", "fra")))) {
            List<FfmpegMedia.SubtitleTrack> tracks = media.subtitleTracks();
            assertEquals(2, tracks.size());
            assertEquals("eng", tracks.get(0).language());
            assertEquals("fra", tracks.get(1).language());
            assertEquals("mov_text", tracks.get(0).codec());
            assertTrue(tracks.get(0).text(), "mov_text is a text format");
            assertTrue(tracks.get(0).decodable(), "and this build has its decoder");
            // The position in the listing, not the container's stream index: the audio track is
            // stream 1, so these are streams 2 and 3 and tracks 0 and 1.
            assertEquals(0, tracks.get(0).index());
            assertEquals(1, tracks.get(1).index());
            assertTrue(tracks.get(1).streamIndex() > tracks.get(0).streamIndex());
        }
    }

    @Test
    void aTrackWithNoLanguageReportsNullAndNotTheUndeterminedCode() throws IOException {
        FfmpegTests.requireWriter();
        // An MP4 written with no language on a track reads back as "und", the ISO 639-2
        // undetermined code, and not as an absent tag, so both have to arrive as null.
        try (FfmpegMedia media = FfmpegMedia.open(clip(20, java.util.Collections.singletonList(null)))) {
            assertEquals(1, media.subtitleTracks().size());
            assertEquals(null, media.subtitleTracks().get(0).language());
        }
    }

    @Test
    void nothingIsSelectedWhenAContainerOpens() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(40, List.of("eng")))) {
            assertEquals(FfmpegMedia.NO_SUBTITLES, media.selectedSubtitleTrack(),
                    "whether a viewer wants subtitles is not a fact about the file");
            assertTrue(media.subtitles().activeAt(0).isEmpty());
            assertSame(List.of(), media.subtitles().activeAt(0),
                    "and asking again allocates nothing");
        }
    }

    @Test
    void anUnselectedTrackQueuesNothingWhileTheFilmRuns() throws IOException {
        FfmpegTests.requireWriter();
        // ADR 012 §7's rule, applied a third time: a track nobody reads has its packets freed as
        // they are demultiplexed. Without it a release carrying a dozen subtitle languages would
        // accumulate eleven queues nobody drains, for the length of the film.
        try (FfmpegMedia media = FfmpegMedia.open(clip(60, List.of("eng", "fra")))) {
            assertEquals(60, FfmpegTests.runVideo(media.video(), 200));
            assertEquals(0, media.queuedSubtitlePackets());
            assertEquals(0, media.droppedSubtitlePackets());
        }
    }

    @Test
    void selectingTheSecondTrackLeavesTheFirstOneQueueingNothing() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(60, List.of("eng", "fra")))) {
            media.selectSubtitles(1);
            assertEquals(1, media.selectedSubtitleTrack());
            FfmpegTests.runVideo(media.video(), 200);
            for (SubtitleCues.Cue cue : media.subtitles().held()) {
                assertTrue(cue.text().startsWith("T1 "),
                        "track 1 was selected, so every cue is track 1's: " + cue.text());
            }
        }
    }

    // ------------------------------------------------------------------ what a cue carries

    @Test
    void aCueCarriesPlainTextWithItsMarkupRemoved() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(40, List.of("eng")))) {
            media.selectSubtitles(0);
            FfmpegTests.runVideo(media.video(), 200);

            // Cue 0 is the one the writer gives override tags and a hard break to. What is stored
            // in the file, and what an application would draw if this SPI handed markup over, is
            //     0,0,Default,,0,0,0,,{\i1}T0 C0{\r}\Nsecond line
            List<SubtitleCues.Cue> at0 = media.subtitles().activeAt(0);
            assertEquals(1, at0.size());
            assertEquals(FfmpegTests.cueTextOf(0, 0), at0.get(0).text());
            assertEquals("T0 C0\nsecond line", at0.get(0).text());
            assertFalse(at0.get(0).text().contains("{"), "no override run survives");
            assertFalse(at0.get(0).text().contains("Default"), "nor the dialogue fields");
            assertFalse(at0.get(0).text().contains("\\N"), "and \\N became a real line break");

            // And a line that never had any is not damaged by the stripping.
            assertEquals("T0 C1", media.subtitles().activeAt(FfmpegTests.insideCue(1)).get(0).text());
        }
    }

    @Test
    void aCueIsTimedOnTheSameTimelineAsAPicture() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(40, List.of("eng")))) {
            media.selectSubtitles(0);
            FfmpegTests.runVideo(media.video(), 200);
            List<SubtitleCues.Cue> held = media.subtitles().held();
            assertEquals(4, held.size(), "40 pictures, a cue every ten");
            assertEquals(0, held.get(0).startMicros());
            for (int i = 1; i < held.size(); i++) {
                assertEquals(held.get(i - 1).endMicros(), held.get(i).startMicros(),
                        "the writer's cues are contiguous, which is what makes the seek "
                                + "assertion about a string rather than about an interval");
            }
        }
    }

    @Test
    void activeAtHandsBackTheSameListWhileTheActiveSetIsUnchanged() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(40, List.of("eng")))) {
            media.selectSubtitles(0);
            FfmpegTests.runVideo(media.video(), 200);
            SubtitleCues cues = media.subtitles();
            List<SubtitleCues.Cue> first = cues.activeAt(FfmpegTests.insideCue(1));
            assertSame(first, cues.activeAt(FfmpegTests.insideCue(1) + 1000),
                    "a paint loop polling every frame must allocate nothing");
            assertNotNull(first);
            assertEquals(1, first.size());
        }
    }

    @Test
    void aTrackThatIsAllGapsProducesNoEmptyCue() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(40, List.of("eng")))) {
            media.selectSubtitles(0);
            FfmpegTests.runVideo(media.video(), 200);
            for (SubtitleCues.Cue cue : media.subtitles().held()) {
                assertFalse(cue.text().isEmpty(), "a gap is not a cue");
            }
        }
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    void selectingATrackThatDoesNotExistIsRefused() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(20, List.of("eng")))) {
            assertThrows(IndexOutOfBoundsException.class, () -> media.selectSubtitles(1));
        }
    }

    @Test
    void turningSubtitlesOffEmptiesTheWindow() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(40, List.of("eng")))) {
            media.selectSubtitles(0);
            FfmpegTests.runVideo(media.video(), 200);
            assertFalse(media.subtitles().activeAt(0).isEmpty());

            media.selectSubtitles(FfmpegMedia.NO_SUBTITLES);
            assertEquals(FfmpegMedia.NO_SUBTITLES, media.selectedSubtitleTrack());
            assertTrue(media.subtitles().activeAt(0).isEmpty(),
                    "a cue left on screen after the viewer turned subtitles off is the one thing "
                            + "turning them off has to prevent");
            assertTrue(media.subtitles().held().isEmpty());
        }
    }

    @Test
    void aContainerWithNoSubtitlesAnswersEverythingWithoutThrowing() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(20, List.of()))) {
            assertTrue(media.subtitleTracks().isEmpty());
            assertEquals(FfmpegMedia.NO_SUBTITLES, media.selectedSubtitleTrack());
            assertNotNull(media.subtitles());
            assertTrue(media.subtitles().activeAt(0).isEmpty());
            assertEquals(0, media.queuedSubtitlePackets());
        }
    }

    // ------------------------------------------------------------------ the seek

    @Test
    void aSeekDoesNotLeaveACueFromWhereTheFilmUsedToBe() throws IOException {
        FfmpegTests.requireWriter();
        // The assertion this whole delivery model turns on. The clip's cues are contiguous and each
        // one names its own index, so "the cue on screen belongs where the film is" is a string
        // comparison: at 0 it is C0, and after a seek to the fourth cue it must be C4 and never C0.
        //
        // Without the epoch that placeDemuxer bumps, the window keeps every cue it decoded before
        // the seek, so activeAt(0) after seeking away still answers C0, and a scrub backwards
        // leaves the line that was up before it standing over the new position.
        try (FfmpegMedia media = FfmpegMedia.open(clip(80, List.of("eng")))) {
            VideoStreamSource video = media.video();
            media.selectSubtitles(0);

            FfmpegTests.runVideo(video, 25);
            assertEquals(FfmpegTests.cueTextOf(0, 0), media.subtitles().activeAt(0).get(0).text());

            long target = FfmpegTests.insideCue(4);
            video.seek(target, SeekMode.EXACT);
            FfmpegTests.runVideo(video, 25);

            List<SubtitleCues.Cue> now = media.subtitles().activeAt(target);
            assertEquals(1, now.size(), "exactly one cue covers every instant of this clip");
            assertEquals(FfmpegTests.cueTextOf(0, 4), now.get(0).text());

            // And the cues from before the seek are gone rather than merely out of the way: asking
            // about where the film used to be answers nothing, because nothing there is known any
            // more. That is the half a window that only pruned would still get wrong.
            assertTrue(media.subtitles().activeAt(0).isEmpty(),
                    "the cues decoded before the seek describe a position the film has left");
        }
    }

    @Test
    void aSeekBackwardsBringsTheEarlierCuesBack() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(80, List.of("eng")))) {
            VideoStreamSource video = media.video();
            media.selectSubtitles(0);
            video.seek(FfmpegTests.insideCue(5), SeekMode.EXACT);
            FfmpegTests.runVideo(video, 25);
            assertFalse(media.subtitles().activeAt(FfmpegTests.insideCue(5)).isEmpty());

            video.seek(FfmpegTests.insideCue(1), SeekMode.EXACT);
            FfmpegTests.runVideo(video, 25);
            List<SubtitleCues.Cue> back = media.subtitles().activeAt(FfmpegTests.insideCue(1));
            assertEquals(1, back.size());
            assertEquals(FfmpegTests.cueTextOf(0, 1), back.get(0).text());
        }
    }

    @Test
    void aRestartIsASeekAndTheWindowFollowsIt() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(clip(80, List.of("eng")))) {
            VideoStreamSource video = media.video();
            media.selectSubtitles(0);
            video.seek(FfmpegTests.insideCue(6), SeekMode.EXACT);
            FfmpegTests.runVideo(video, 25);
            assertEquals(FfmpegTests.cueTextOf(0, 6),
                    media.subtitles().activeAt(FfmpegTests.insideCue(6)).get(0).text());

            video.reset();
            FfmpegTests.runVideo(video, 25);
            assertEquals(FfmpegTests.cueTextOf(0, 0),
                    media.subtitles().activeAt(0).get(0).text());
            assertTrue(media.subtitles().activeAt(FfmpegTests.insideCue(6)).isEmpty(),
                    "and nothing from the position the film was rewound from is still held");
        }
    }

    @Test
    void cuesFollowThePicturesAndNothingElsePullsThem() throws IOException {
        FfmpegTests.requireWriter();
        // The consequence of the subtitle side never demultiplexing, stated on the SPI and asserted
        // here: a container whose video nobody reads produces no cues, however long it is polled.
        try (FfmpegMedia media = FfmpegMedia.open(clip(40, List.of("eng")))) {
            media.selectSubtitles(0);
            for (int i = 0; i < 50; i++) {
                assertTrue(media.subtitles().activeAt(i * 10_000L).isEmpty());
            }
            assertTrue(media.subtitles().held().isEmpty());

            FfmpegTests.runVideo(media.video(), 200);
            assertFalse(media.subtitles().held().isEmpty(),
                    "and they arrive as soon as the pictures do");
        }
    }

    @Test
    void aClosedContainerAnswersInsteadOfThrowing() throws IOException {
        FfmpegTests.requireWriter();
        FfmpegMedia media = FfmpegMedia.open(clip(20, List.of("eng")));
        media.selectSubtitles(0);
        FfmpegTests.runVideo(media.video(), 200);
        media.close();
        // A paint that asks for the cue over a picture can genuinely arrive after the container was
        // closed. It gets what it had, and nothing new (not an exception from a thread that has no
        // way to handle one).
        assertNotNull(media.subtitles().activeAt(0));
    }
}
