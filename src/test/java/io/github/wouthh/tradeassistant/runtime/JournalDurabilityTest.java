package io.github.wouthh.tradeassistant.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.wouthh.tradeassistant.domain.Model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalDurabilityTest {
    @Test
    void newlyCreatedDirectoryEntriesAreFlushedThroughTheirExistingAncestor() throws Exception {
        Path stateDirectory = temp.resolve("new/nested");
        List<Path> flushed = new ArrayList<>();
        try (LocalState state = new LocalState(stateDirectory, flushed::add)) {
            Snapshot snapshot =
                    new Snapshot(
                            State.DISARMED,
                            false,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            null,
                            null,
                            Set.of(),
                            Set.of(),
                            Map.of(),
                            "synthetic");
            state.save(snapshot);
            assertEquals(List.of(stateDirectory, temp.resolve("new"), temp), flushed);
            flushed.clear();
            state.save(snapshot);
            assertEquals(List.of(stateDirectory), flushed);
        }
    }

    @TempDir Path temp;

    @Test
    void directoryFlushFollowsRenameAndFailureIsReportedToTheCaller() throws Exception {
        AtomicBoolean flushed = new AtomicBoolean();
        try (LocalState state =
                new LocalState(
                        temp,
                        directory -> {
                            assertEquals(temp, directory);
                            assertTrue(
                                    Files.readString(directory.resolve("journal.json"))
                                            .contains("synthetic intent"));
                            flushed.set(true);
                            throw new IOException("synthetic directory flush failure");
                        })) {
            Snapshot intent =
                    new Snapshot(
                            State.ARMED,
                            true,
                            42,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            null,
                            null,
                            Set.of(),
                            Set.of(),
                            Map.of(),
                            "synthetic intent");
            assertThrows(IOException.class, () -> state.save(intent));
            assertTrue(flushed.get());
        }
    }
}
