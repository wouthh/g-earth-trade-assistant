package io.github.wouthh.tradeassistant.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotClassLoaderTest {
    @TempDir Path temp;

    private Path archive(String identity) throws Exception {
        Path source = temp.resolve(identity);
        Files.createDirectories(source.resolve("fixture"));
        Files.writeString(
                source.resolve("fixture/Anchor.java"),
                "package fixture; public class Anchor { public static String value() { return \""
                        + identity
                        + "\"; } public static String lazy() { return Lazy.value(); } }");
        Files.writeString(
                source.resolve("fixture/Lazy.java"),
                "package fixture; public class Lazy { public static String value() { return \""
                        + identity
                        + "\"; } }");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "-d",
                                source.toString(),
                                source.resolve("fixture/Anchor.java").toString(),
                                source.resolve("fixture/Lazy.java").toString()));
        Path result = temp.resolve(identity + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var output = new JarOutputStream(Files.newOutputStream(result), manifest)) {
            for (String name : new String[] {"Anchor", "Lazy"}) {
                output.putNextEntry(new JarEntry("fixture/" + name + ".class"));
                output.write(Files.readAllBytes(source.resolve("fixture/" + name + ".class")));
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry("fixture/value.txt"));
            output.write(identity.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/tradeassistant-build.properties"));
            output.write(
                    ("source=" + identity.repeat(40) + "\nversion=0.1.1\n")
                            .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return result;
    }

    @Test
    void atomicPathReplacementCannotChangeEagerLazyResourceOrArtifactIdentity() throws Exception {
        Path a = archive("a");
        Path b = archive("b");
        String aHash = LoadedIdentity.digest(a);
        String bHash = LoadedIdentity.digest(b);
        SnapshotClassLoader loader = new SnapshotClassLoader(a);
        Class<?> anchor = loader.loadClass("fixture.Anchor");
        assertEquals("a", anchor.getMethod("value").invoke(null));
        Files.move(b, a, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(bHash, LoadedIdentity.digest(a));
        assertEquals("a", anchor.getMethod("value").invoke(null));
        assertEquals("a", anchor.getMethod("lazy").invoke(null));
        assertEquals(aHash, loader.artifactSha256());
        assertEquals("a".repeat(40), loader.source());
        try (var input = loader.getResourceAsStream("fixture/value.txt")) {
            assertEquals("a", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var input = loader.getResources("fixture/value.txt").nextElement().openStream()) {
            assertEquals("a", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertSame(String.class, loader.loadClass("java.lang.String"));
        assertSame(
                SnapshotClassLoader.class, loader.loadClass(SnapshotClassLoader.class.getName()));
        assertThrows(
                ClassNotFoundException.class,
                () -> loader.loadClass(LoadedIdentity.class.getName()));
        assertNull(loader.getResource("org/json/JSONObject.class"));
    }

    @Test
    void inPlaceOverwriteCannotLazyLoadBootstrapResourceBytecode() throws Exception {
        Path jar = archive("a");
        String bootstrapName = SnapshotClassLoader.class.getName();
        String bootstrapPath = bootstrapName.replace('.', '/');
        Path classes =
                Path.of(
                        SnapshotClassLoader.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        var original = new java.io.ByteArrayOutputStream();
        try (var input = new java.util.jar.JarFile(jar.toFile());
                var output = new JarOutputStream(original)) {
            var all = input.entries();
            while (all.hasMoreElements()) {
                var entry = all.nextElement();
                output.putNextEntry(new JarEntry(entry.getName()));
                try (var bytes = input.getInputStream(entry)) {
                    bytes.transferTo(output);
                }
                output.closeEntry();
            }
            try (var helpers = Files.list(classes.resolve(bootstrapPath).getParent())) {
                for (Path helper :
                        helpers.filter(
                                        p ->
                                                p.getFileName()
                                                                .toString()
                                                                .startsWith("SnapshotClassLoader")
                                                        && p.getFileName()
                                                                .toString()
                                                                .endsWith(".class"))
                                .toList()) {
                    output.putNextEntry(
                            new JarEntry(classes.relativize(helper).toString().replace('\\', '/')));
                    output.write(Files.readAllBytes(helper));
                    output.closeEntry();
                }
            }
        }
        Files.write(jar, original.toByteArray());
        var replacement = new java.io.ByteArrayOutputStream();
        try (var input =
                        new java.util.jar.JarInputStream(
                                new java.io.ByteArrayInputStream(original.toByteArray()));
                var output = new JarOutputStream(replacement, input.getManifest())) {
            java.util.jar.JarEntry entry;
            while ((entry = input.getNextJarEntry()) != null) {
                if (entry.getName().startsWith(bootstrapPath + "$")) continue;
                output.putNextEntry(new JarEntry(entry.getName()));
                input.transferTo(output);
                output.closeEntry();
            }
        }
        try (var application =
                new java.net.URLClassLoader(
                        new java.net.URL[] {jar.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            Class<?> bootstrap = application.loadClass(bootstrapName);
            ClassLoader snapshot =
                    (ClassLoader) bootstrap.getConstructor(Path.class).newInstance(jar);
            Object before =
                    Files.readAttributes(jar, java.nio.file.attribute.BasicFileAttributes.class)
                            .fileKey();
            Files.write(jar, replacement.toByteArray());
            assertEquals(
                    before,
                    Files.readAttributes(jar, java.nio.file.attribute.BasicFileAttributes.class)
                            .fileKey());
            try (var input = snapshot.getResourceAsStream("fixture/value.txt")) {
                assertNotNull(input);
                assertEquals("a", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var input =
                    snapshot.getResources("fixture/value.txt").nextElement().openStream()) {
                assertEquals("a", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void directoryPayloadCannotBypassTheExpansionBound() throws Exception {
        Path malicious = temp.resolve("directory-payload.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(malicious))) {
            output.putNextEntry(new JarEntry("directory/"));
            byte[] block = new byte[1_000_000];
            for (int i = 0; i < 65; i++) output.write(block);
            output.closeEntry();
        }
        var failure =
                assertThrows(java.io.IOException.class, () -> new SnapshotClassLoader(malicious));
        assertEquals("Snapshot directory has payload", failure.getMessage());
        assertTrue(Files.size(malicious) < 100_000);
    }

    @Test
    void malformedAndExternalClasspathsCannotCreateSnapshotEvidence() throws Exception {
        Path good = archive("a");
        for (String extra :
                new String[] {"Class-Path: unrelated.jar\r\n", "Multi-Release: true\r\n"}) {
            Path bad = temp.resolve("external.jar");
            try (var input = new java.util.jar.JarFile(good.toFile());
                    var output = new JarOutputStream(Files.newOutputStream(bad))) {
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    output.putNextEntry(new JarEntry(entry.getName()));
                    if (entry.getName().equals("META-INF/MANIFEST.MF"))
                        output.write(
                                ("Manifest-Version: 1.0\r\n" + extra + "\r\n")
                                        .getBytes(StandardCharsets.UTF_8));
                    else
                        try (var bytes = input.getInputStream(entry)) {
                            bytes.transferTo(output);
                        }
                    output.closeEntry();
                }
            }
            assertThrows(Exception.class, () -> new SnapshotClassLoader(bad));
        }
        Path malformed = temp.resolve("malformed.jar");
        Files.writeString(malformed, "not a zip");
        assertThrows(Exception.class, () -> new SnapshotClassLoader(malformed));
    }
}
