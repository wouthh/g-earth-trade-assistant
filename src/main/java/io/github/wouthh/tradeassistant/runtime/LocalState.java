package io.github.wouthh.tradeassistant.runtime;

import io.github.wouthh.tradeassistant.domain.Model.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.json.JSONObject;

/** Owner-local state, atomic replacement and one process per state directory. */
public final class LocalState implements Journal, AutoCloseable {
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private final String run = UUID.randomUUID().toString();
    private JSONObject previous;
    private JSONObject last;
    private int runRoom;

    public LocalState(Path directory) throws IOException {
        this.directory = directory;
        if (Files.isSymbolicLink(directory))
            throw new IOException("State directory must not be a symlink");
        Files.createDirectories(directory);
        privatePermissions(directory, true);
        if (Files.isSymbolicLink(directory.resolve("session.lock")))
            throw new IOException("Lock file must not be a symlink");
        channel =
                FileChannel.open(
                        directory.resolve("session.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new IOException("Another extension owns this state directory");
        }
        if (acquired == null) {
            channel.close();
            throw new IOException("Another extension owns this state directory");
        }
        lock = acquired;
        Path latest = directory.resolve("journal.json");
        try {
            previous = Files.exists(latest) ? readJson(latest) : new JSONObject();
        } catch (IOException | RuntimeException e) {
            previous =
                    new JSONObject()
                            .put("recovery", true)
                            .put(
                                    "message",
                                    "Previous journal is unreadable. Review local files manually.");
        }
    }

    public static Path defaultDirectory() {
        String configured = System.getProperty("tradeassistant.stateDir");
        if (configured != null) return Path.of(configured);
        String windows = System.getenv("LOCALAPPDATA");
        if (windows != null) return Path.of(windows, "G-Earth Trade Assistant");
        String xdg = System.getenv("XDG_STATE_HOME");
        return (xdg != null
                        ? Path.of(xdg)
                        : Path.of(System.getProperty("user.home"), ".local", "state"))
                .resolve("g-earth-trade-assistant");
    }

    public boolean needsReview() {
        return previous.optBoolean("recovery")
                || previous.optJSONArray("pending") != null
                        && previous.getJSONArray("pending").length() > 0
                || previous.optJSONArray("unredeemed") != null
                        && previous.getJSONArray("unredeemed").length() > 0;
    }

    public String recoverySummary() {
        return needsReview()
                ? "Previous journal: pending handles "
                        + previous.optJSONArray("pending")
                        + "; unredeemed object IDs "
                        + previous.optJSONArray("unredeemed")
                        + ". No auto-resume."
                : "";
    }

    @Override
    public void save(Snapshot s) throws IOException {
        if (runRoom == 0 && s.observed() > 0 && s.roomId() > 0) runRoom = s.roomId();
        JSONObject j =
                new JSONObject()
                        .put("schema", 1)
                        .put("run", run)
                        .put("state", s.state().name())
                        .put("room", s.roomId())
                        .put("runRoom", runRoom)
                        .put("operations", s.operations())
                        .put("pending", s.pendingHandles())
                        .put("unredeemed", s.unredeemed())
                        .put("redeemed", s.redeemed())
                        .put("placed", s.placed())
                        .put("observed", s.observed())
                        .put("recovery", s.state() == State.UNCERTAIN)
                        .put("message", s.message());
        // Preserve prior run evidence before the current process replaces its pointer.
        if (!previous.isEmpty()) {
            Path archive = directory.resolve("previous-" + UUID.randomUUID() + ".json");
            atomic(archive, previous.toString(2));
            previous = new JSONObject();
        }
        if (last != null
                && !last.getJSONObject("operations").isEmpty()
                && s.operations().isEmpty()) {
            atomic(directory.resolve("previous-" + UUID.randomUUID() + ".json"), last.toString(2));
            runRoom = 0;
            j.put("runRoom", 0);
        }
        atomic(directory.resolve("journal.json"), j.toString(2));
        last = j;
    }

    public Properties preferences() throws IOException {
        Properties p = new Properties();
        Path f = directory.resolve("preferences.properties");
        if (Files.exists(f)) {
            if (Files.isSymbolicLink(f) || Files.size(f) > 16384)
                throw new IOException("Invalid preferences file");
            try (Reader r = Files.newBufferedReader(f)) {
                p.load(r);
            }
        }
        return p;
    }

    public void preferences(int quantity, long pacing, long timeout) throws IOException {
        new Config(Mode.MANUAL_DROPS, quantity, pacing, timeout, false);
        atomic(
                directory.resolve("preferences.properties"),
                "quantity=" + quantity + "\npacing=" + pacing + "\ntimeout=" + timeout + "\n");
    }

    private JSONObject readJson(Path p) throws IOException {
        if (Files.isSymbolicLink(p) || Files.size(p) > 1_000_000)
            throw new IOException("Invalid local journal");
        JSONObject j = new JSONObject(Files.readString(p));
        if (j.getInt("schema") != 1
                || j.getJSONArray("pending").length() > 1000
                || j.getJSONArray("unredeemed").length() > 1000)
            throw new IOException("Unsupported journal schema");
        State.valueOf(j.getString("state"));
        return j;
    }

    private void atomic(Path destination, String text) throws IOException {
        if (Files.isSymbolicLink(destination))
            throw new IOException("State file must not be a symlink");
        Path temporary = Files.createTempFile(directory, "write-", ".tmp");
        try {
            privatePermissions(temporary, false);
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes =
                        java.nio.ByteBuffer.wrap(
                                text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) out.write(bytes);
                out.force(true);
            }
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void privatePermissions(Path p, boolean dir) throws IOException {
        if (Files.getFileStore(p).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(
                    p, PosixFilePermissions.fromString(dir ? "rwx------" : "rw-------"));
    }

    @Override
    public void close() throws IOException {
        lock.release();
        channel.close();
    }
}
