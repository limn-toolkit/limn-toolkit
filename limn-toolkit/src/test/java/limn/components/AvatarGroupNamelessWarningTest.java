package limn.components;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A group of avatars with no name is a group a reader cannot place on the page, so it says so in
 * the log, once, as a card activated by a click with no title does.
 */
class AvatarGroupNamelessWarningTest extends AccessibleComponentTestBase {

    private final Logger logger = Logger.getLogger(AvatarGroup.class.getName());
    private final List<LogRecord> logged = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void listen() {
        logger.addHandler(capture);
    }

    @AfterEach
    void stop() {
        logger.removeHandler(capture);
    }

    @Test
    void anUnnamedGroupWarnsOnce() {
        AvatarGroup group = new AvatarGroup(new Avatar("Ana Lima"), new Avatar("Rui Costa"));
        bind(group);
        frame();
        group.add(new Avatar("Mei Chen"));
        frame();
        assertEquals(1, logged.size(), "once: " + logged);
    }

    @Test
    void aNamedGroupSaysNothing() {
        AvatarGroup group = new AvatarGroup(new Avatar("Ana Lima")).setAccessibleName("Approvers");
        bind(group);
        frame();
        assertEquals(0, logged.size());
    }
}
