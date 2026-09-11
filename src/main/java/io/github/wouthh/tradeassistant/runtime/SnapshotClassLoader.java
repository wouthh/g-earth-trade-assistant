package io.github.wouthh.tradeassistant.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.SecureClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/** Owns the exact bounded bytes used to load extension code and resources. */
public final class SnapshotClassLoader extends SecureClassLoader {
    private static final int LIMIT = 64 * 1024 * 1024;
    private static final Thread.UncaughtExceptionHandler FATAL_HANDLER =
            (thread, failure) -> {
                if (!(failure instanceof ThreadDeath))
                    System.err.println("Extension stopped after a fatal runtime error.");
            };
    private static final String BUILD = "META-INF/tradeassistant-build.properties";
    private static final Pattern PROVENANCE =
            Pattern.compile(
                    "source=([0-9a-f]{40}|unverified)\\r?\\nversion=([0-9]+\\.[0-9]+\\.[0-9]+)\\r?\\n?");
    private final Path artifact;
    private final Map<String, byte[]> entries;
    private final String artifactSha256;
    private final String source;
    private final String version;
    private final CodeSource codeSource;

    public SnapshotClassLoader(Path path) throws Exception {
        super(ClassLoader.getPlatformClassLoader());
        // Resolve every trusted resource helper before capturing mutable JAR bytes.
        ResourceHandler.warmUp();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Snapshot requires a regular packaged JAR");
        artifact = path.toRealPath();
        byte[] archive;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            archive = input.readNBytes(LIMIT + 1);
        }
        if (archive.length > LIMIT) throw new IOException("Snapshot archive exceeds its bound");
        artifactSha256 =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
        Map<String, byte[]> found = new HashMap<>();
        var names = new java.util.HashSet<String>();
        int total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!names.add(name)
                        || names.size() > 10000
                        || name.startsWith("/")
                        || name.contains("\\")
                        || java.util.Arrays.asList(name.split("/", -1)).contains(".."))
                    throw new IOException("Invalid snapshot entry");
                if (entry.isDirectory()) {
                    if (zip.read() != -1) throw new IOException("Snapshot directory has payload");
                    continue;
                }
                byte[] bytes = zip.readNBytes(LIMIT - total + 1);
                total += bytes.length;
                if (total > LIMIT) throw new IOException("Snapshot expansion exceeds its bound");
                found.put(name, bytes);
            }
        }
        byte[] manifestBytes = found.get("META-INF/MANIFEST.MF");
        if (manifestBytes == null) throw new IOException("Snapshot manifest missing");
        var attributes = new Manifest(new ByteArrayInputStream(manifestBytes)).getMainAttributes();
        if (attributes.getValue("Class-Path") != null
                || attributes.getValue("Multi-Release") != null)
            throw new IOException("External or multi-release loading is unsupported");
        byte[] provenance = found.get(BUILD);
        if (provenance == null || provenance.length > 4096)
            throw new IOException("Snapshot provenance unavailable");
        var match = PROVENANCE.matcher(new String(provenance, StandardCharsets.US_ASCII));
        if (!match.matches()) throw new IOException("Snapshot provenance is noncanonical");
        source = match.group(1);
        version = match.group(2);
        entries = Map.copyOf(found);
        codeSource =
                new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null);
    }

    public Path artifact() {
        return artifact;
    }

    public String artifactSha256() {
        return artifactSha256;
    }

    public String source() {
        return source;
    }

    public String version() {
        return version;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // The eagerly loaded JDK-only bootstrap types bridge the two loaders. No product,
        // API or dependency class can fall back to the mutable application classpath.
        if (name.equals(SnapshotClassLoader.class.getName())
                || name.equals(SnapshotClassLoader.class.getName() + "$ResourceHandler")
                || name.equals(SnapshotClassLoader.class.getName() + "$ResourceConnection"))
            return SnapshotClassLoader.class.getClassLoader().loadClass(name);
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = entries.get(name.replace('.', '/') + ".class");
        if (bytes == null) throw new ClassNotFoundException(name);
        return defineClass(name, bytes, 0, bytes.length, codeSource);
    }

    @Override
    protected URL findResource(String name) {
        byte[] bytes = entries.get(name);
        if (bytes == null) return null;
        try {
            return new URL(null, "snapshot:/" + name, new ResourceHandler(bytes));
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Invalid snapshot resource", e);
        }
    }

    private static final class ResourceHandler extends URLStreamHandler {
        private final byte[] bytes;

        static void warmUp() {
            ResourceConnection.warmUp();
        }

        ResourceHandler(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        protected URLConnection openConnection(URL url) {
            return new ResourceConnection(url, bytes);
        }
    }

    private static final class ResourceConnection extends URLConnection {
        private final byte[] bytes;

        static void warmUp() {
            // Invocation initializes this trusted bootstrap type before capture.
        }

        ResourceConnection(URL url, byte[] bytes) {
            super(url);
            this.bytes = bytes;
        }

        @Override
        public void connect() {
            connected = true;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        URL resource = findResource(name);
        return Collections.enumeration(
                resource == null ? java.util.List.of() : java.util.List.of(resource));
    }

    /** JDK-only manifest entrypoint; product code cannot initialize before the snapshot. */
    public static void main(String[] arguments) {
        ClassLoader originalContext = Thread.currentThread().getContextClassLoader();
        java.lang.reflect.Method runtimeMain;
        try {
            var location =
                    SnapshotClassLoader.class.getProtectionDomain().getCodeSource().getLocation();
            var snapshot = new SnapshotClassLoader(Path.of(location.toURI()));
            Thread.currentThread().setContextClassLoader(snapshot);
            var runtime =
                    Class.forName(
                            "io.github.wouthh.tradeassistant.protocol.TradeAssistantExtension",
                            true,
                            snapshot);
            runtimeMain = runtime.getMethod("main", String[].class);
        } catch (Throwable invalidArchive) {
            Thread.currentThread().setContextClassLoader(originalContext);
            reportFailure(invalidArchive, false);
            return;
        }
        try {
            runtimeMain.invoke(null, (Object) arguments);
        } catch (java.lang.reflect.InvocationTargetException runtimeFailure) {
            reportFailure(runtimeFailure.getCause(), true);
        } catch (Throwable invocationFailure) {
            reportFailure(invocationFailure, false);
        } finally {
            Thread.currentThread().setContextClassLoader(originalContext);
        }
    }

    private static void reportFailure(Throwable failure, boolean invokedRuntime) {
        if (failure instanceof VirtualMachineError fatal) {
            Thread.currentThread().setUncaughtExceptionHandler(FATAL_HANDLER);
            throw fatal;
        }
        if (failure instanceof ThreadDeath terminated) {
            Thread.currentThread().setUncaughtExceptionHandler(FATAL_HANDLER);
            throw terminated;
        }
        boolean runtimeFailure = invokedRuntime && !(failure instanceof LinkageError);
        System.err.println(
                runtimeFailure
                        ? "Extension runtime stopped unexpectedly."
                        : "Extension archive could not be loaded safely.");
        System.exit(runtimeFailure ? 3 : 2);
    }
}
