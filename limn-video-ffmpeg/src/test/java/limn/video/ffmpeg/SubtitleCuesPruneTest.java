package limn.video.ffmpeg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The window of held cues forgets the ones long gone even behind a cue that is not: it is in
 * start order, and pruning only from its front let one long cue keep every short one after it.
 * Needs no native, unlike the rest of the subtitle tests.
 */
class SubtitleCuesPruneTest {

    private static final long SECOND = 1_000_000L;

    @Test
    void aLongCueAtTheFrontDoesNotKeepTheShortOnesBehindIt() {
        List<SubtitleCues.Cue> window = new ArrayList<>();
        SubtitleCues.Cue title = new SubtitleCues.Cue("Title", 0, 7200 * SECOND);
        window.add(title);
        for (int i = 1; i <= 1000; i++) {
            window.add(new SubtitleCues.Cue("Line " + i, i * 5 * SECOND, i * 5 * SECOND + 4 * SECOND));
        }
        SubtitleCues.prune(window, 3000 * SECOND);

        assertEquals(title, window.get(0), "the cue still on screen stays, and stays first");
        assertEquals("Line 594", window.get(1).text(),
                "the first line kept is the first that ended within thirty seconds");
        assertEquals(1 + 407, window.size(), "those since, and those still to come");
    }
}
