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
    private static final int MAX_ARCHIVES = 32;
    private static final long MAX_ARCHIVE_BYTES = 32_000_000;

    @FunctionalInterface
    interface DirectorySync {
        void force(Path directory) throws IOException;
    }

    private final DirectorySync directorySync;
    private final List<Path> createdDirectoryParents = new ArrayList<>();
    private boolean preserveOriginal;
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private final String run = UUID.randomUUID().toString();
    private JSONObject previous;
    private JSONObject last;
    private int runRoom;

    public LocalState(Path directory) throws IOException {
        this(directory, LocalState::forceDirectory);
    }

    LocalState(Path directory, DirectorySync directorySync) throws IOException {
        this.directory = directory;
        this.directorySync = directorySync;
        if (Files.isSymbolicLink(directory))
            throw new IOException("State directory must not be a symlink");
        for (Path missing = directory.toAbsolutePath();
                !Files.exists(missing);
                missing = missing.getParent()) {
            if (missing.getParent() == null) break;
            createdDirectoryParents.add(missing.getParent());
        }
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
            preserveOriginal = true;
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
        return needsReview(previous);
    }

    private static boolean needsReview(JSONObject journal) {
        return journal.optBoolean("recovery")
                || journal.optJSONArray("pending") != null
                        && journal.getJSONArray("pending").length() > 0
                || journal.optJSONArray("unredeemed") != null
                        && journal.getJSONArray("unredeemed").length() > 0;
    }

    public String recoverySummary() {
        if (preserveOriginal)
            return "Previous journal is unreadable. Its original bytes must be preserved before replacement. Review journal.json or its previous-*.json archive manually; no auto-resume.";
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
        // Archive only recovery evidence; never manufacture a replacement for unreadable bytes.
        if (preserveOriginal) {
            Path original = directory.resolve("journal.json");
            if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(original) > 1_000_000)
                throw new IOException(
                        "Original journal cannot be safely archived; preserve it manually before replacement");
            archive(Files.readAllBytes(original));
            preserveOriginal = false;
            previous = new JSONObject();
        } else if (!previous.isEmpty()) {
            if (needsReview(previous))
                archive(previous.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            previous = new JSONObject();
        }
        if (last != null
                && !last.getJSONObject("operations").isEmpty()
                && s.operations().isEmpty()) {
            if (needsReview(last))
                archive(last.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private void archive(byte[] bytes) throws IOException {
        int count = 0;
        long total = bytes.length;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "previous-*.json")) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unexpected recovery archive entry; inspect local state");
                total += Files.size(file);
                if (++count >= MAX_ARCHIVES || total > MAX_ARCHIVE_BYTES)
                    throw new IOException(
                            "Recovery archive capacity reached. Export and reconcile existing archives manually before continuing");
            }
        }
        if (total > MAX_ARCHIVE_BYTES) throw new IOException("Recovery archive exceeds capacity");
        atomic(directory.resolve("previous-" + UUID.randomUUID() + ".json"), bytes);
    }

    void runtimeIdentity(String text) throws IOException {
        if (text.length() > 4096) throw new IOException("Identity receipt exceeds its bound");
        Path receipt = directory.resolve("runtime-identity.json");
        if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) LoadedIdentity.read(receipt);
        atomic(receipt, text);
    }

    private void atomic(Path destination, String text) throws IOException {
        atomic(destination, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void atomic(Path destination, byte[] content) throws IOException {
        if (content.length > 1_000_000) throw new IOException("Local state exceeds its size limit");
        if (Files.isSymbolicLink(destination))
            throw new IOException("State file must not be a symlink");
        Path temporary = Files.createTempFile(directory, "write-", ".tmp");
        try {
            privatePermissions(temporary, false);
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes = java.nio.ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) out.write(bytes);
                out.force(true);
            }
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            directorySync.force(directory);
            // Persist newly created ancestor entries as well as the journal rename.
            for (Path parent : createdDirectoryParents) directorySync.force(parent);
            createdDirectoryParents.clear();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
            dir.force(true);
        } catch (AccessDeniedException | UnsupportedOperationException e) {
            // Windows' Java file provider cannot normally open directories as FileChannels.
            // Linux and other supported-provider I/O failures must stop submission.
            if (!System.getProperty("os.name", "").startsWith("Windows"))
                throw new IOException("Directory durability flush failed", e);
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
