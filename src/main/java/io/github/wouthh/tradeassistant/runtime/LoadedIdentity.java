package io.github.wouthh.tradeassistant.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import org.json.JSONObject;

/** Passive lifecycle evidence. A receipt alone never establishes current liveness. */
public final class LoadedIdentity implements AutoCloseable {
    private static final String COMPONENT = "g-earth-trade-assistant";
    private static final String RESOURCE = "/META-INF/tradeassistant-build.properties";
    private static final Set<String> FIELDS =
            Set.of(
                    "schema",
                    "component",
                    "version",
                    "source",
                    "artifactSha256",
                    "executableSha256",
                    "pid",
                    "processStarted",
                    "state");
    private final LocalState store;
    private final JSONObject receipt;

    public static final class UnverifiedBuild extends IOException {
        public UnverifiedBuild() {
            super("Build provenance is unverified");
        }
    }

    private LoadedIdentity(LocalState store, JSONObject receipt) {
        this.store = store;
        this.receipt = receipt;
    }

    public static LoadedIdentity start(LocalState store, Class<?> anchor) throws Exception {
        Path artifact = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("A packaged JAR is required for loaded identity");
        Path packageRoot = artifact.toRealPath().getParent();
        if (packageRoot.getFileName().toString().equals("extension"))
            packageRoot = packageRoot.getParent();
        if (LocalState.defaultDirectory().toRealPath().startsWith(packageRoot))
            throw new IOException("Identity state must be outside the package directory");
        Properties build = metadata(artifact);
        String source = build.getProperty("source", "");
        String version = build.getProperty("version", "");
        if (!source.matches("[0-9a-f]{40}") || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new UnverifiedBuild();
        ProcessHandle process = ProcessHandle.current();
        var info = process.info();
        JSONObject value =
                new JSONObject()
                        .put("schema", 1)
                        .put("component", COMPONENT)
                        .put("version", version)
                        .put("source", source)
                        .put("artifactSha256", digest(artifact))
                        .put("executableSha256", digest(Path.of(info.command().orElseThrow())))
                        .put("pid", process.pid())
                        .put("processStarted", info.startInstant().orElseThrow().toString())
                        .put("state", "loaded");
        store.runtimeIdentity(value.toString());
        return new LoadedIdentity(store, value);
    }

    @Override
    public void close() throws IOException {
        receipt.put("state", "stopped");
        store.runtimeIdentity(receipt.toString());
    }

    static String digest(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 64_000_000)
            throw new IOException("Identity input must be a bounded regular file");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[16384];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 64_000_000)
                    throw new IOException("Identity input grew beyond its bound");
                hash.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static Properties metadata(Path artifact) throws IOException {
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                || Files.size(artifact) > 64_000_000)
            throw new IOException("Build artifact must be a bounded regular file");
        Properties properties = new Properties();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            if (jar.size() > 10000) throw new IOException("Build artifact entry limit exceeded");
            var entry = jar.getJarEntry(RESOURCE.substring(1));
            if (entry == null || entry.getSize() > 4096)
                throw new IOException("Build provenance is unavailable");
            try (InputStream input = jar.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(4097);
                if (bytes.length > 4096)
                    throw new IOException("Build provenance exceeds its bound");
                properties.load(new java.io.ByteArrayInputStream(bytes));
            }
        }
        return properties;
    }

    static JSONObject read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Receipt is not a regular file");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) throw new IOException("Receipt exceeds its bound");
            JSONObject value =
                    new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!value.keySet().equals(FIELDS)
                    || !(value.get("schema") instanceof Integer)
                    || value.getInt("schema") != 1
                    || !COMPONENT.equals(value.getString("component"))
                    || !value.getString("version").matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                    || !value.getString("source").matches("[0-9a-f]{40}")
                    || !value.getString("artifactSha256").matches("[0-9a-f]{64}")
                    || !value.getString("executableSha256").matches("[0-9a-f]{64}")
                    || !Set.of("loaded", "stopped").contains(value.getString("state"))
                    || !(value.get("pid") instanceof Number)
                    || !value.get("pid").toString().matches("[1-9][0-9]{0,18}")
                    || value.getLong("pid") <= 0) throw new IOException("Invalid identity receipt");
            Instant.parse(value.getString("processStarted"));
            return value;
        } catch (RuntimeException e) {
            throw new IOException("Invalid identity receipt", e);
        }
    }

    public static JSONObject verify(Path receipt, Path artifact, String expectedSource)
            throws Exception {
        JSONObject value = read(receipt);
        Properties build = metadata(artifact);
        if (!expectedSource.matches("[0-9a-f]{40}")
                || !expectedSource.equals(value.getString("source"))
                || !expectedSource.equals(build.getProperty("source"))
                || !value.getString("version").equals(build.getProperty("version"))
                || !"loaded".equals(value.getString("state"))
                || !digest(artifact).equals(value.getString("artifactSha256")))
            throw new IOException("Receipt does not match the expected loaded artifact");
        ProcessHandle process = ProcessHandle.of(value.getLong("pid")).orElseThrow();
        var before = process.info();
        if (!process.isAlive()
                || !before.startInstant()
                        .orElseThrow()
                        .toString()
                        .equals(value.getString("processStarted"))
                || !digest(Path.of(before.command().orElseThrow()))
                        .equals(value.getString("executableSha256")))
            throw new IOException("Loaded process identity does not match");
        if (!process.isAlive()
                || !process.info().startInstant().equals(before.startInstant())
                || !read(receipt).toMap().equals(value.toMap()))
            throw new IOException("Loaded process changed during verification");
        return new JSONObject()
                .put("component", COMPONENT)
                .put("version", value.getString("version"))
                .put("source", expectedSource)
                .put("artifactSha256", value.getString("artifactSha256"))
                .put("loaded", true)
                .put("scope", "process alive at verification; no game or health assertion");
    }
}
