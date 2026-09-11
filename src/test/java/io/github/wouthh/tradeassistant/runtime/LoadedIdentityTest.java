package io.github.wouthh.tradeassistant.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadedIdentityTest {
    @TempDir Path temp;
    private static final String SOURCE = "a".repeat(40);

    @Test
    void genericLoaderCannotPublishLoadedArtifactEvidence() throws Exception {
        try (LocalState state = new LocalState(temp)) {
            assertThrows(
                    LoadedIdentity.UnverifiedBuild.class,
                    () -> LoadedIdentity.start(state, LoadedIdentityTest.class));
            assertFalse(Files.exists(temp.resolve("runtime-identity.json")));
        }
    }

    private Path artifact() throws Exception {
        Path jar = temp.resolve("fixture.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/tradeassistant-build.properties"));
            output.write(
                    ("source=" + SOURCE + "\nversion=0.1.1\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private JSONObject value(Path jar) throws Exception {
        var process = ProcessHandle.current();
        return new JSONObject()
                .put("schema", 1)
                .put("component", "g-earth-trade-assistant")
                .put("version", "0.1.1")
                .put("source", SOURCE)
                .put("artifactSha256", LoadedIdentity.digest(jar))
                .put(
                        "executableSha256",
                        LoadedIdentity.digest(Path.of(process.info().command().orElseThrow())))
                .put("pid", process.pid())
                .put("processStarted", process.info().startInstant().orElseThrow().toString())
                .put("state", "loaded");
    }

    @Test
    void matchesCurrentProcessAndArtifactWithoutReturningPathsOrArguments() throws Exception {
        Path jar = artifact();
        Path receipt = temp.resolve("runtime-identity.json");
        Files.writeString(receipt, value(jar).toString());
        var verified = LoadedIdentity.verify(receipt, jar, SOURCE);
        assertTrue(verified.getBoolean("loaded"));
        assertEquals(SOURCE, verified.getString("source"));
        assertFalse(verified.toString().contains(temp.toString()));
        assertFalse(verified.has("pid"));
    }

    @Test
    void refusesStoppedDeadReusedChangedOrMalformedIdentity() throws Exception {
        Path jar = artifact();
        Path receipt = temp.resolve("runtime-identity.json");
        for (String failure :
                new String[] {
                    "stopped",
                    "dead",
                    "start",
                    "executable",
                    "artifact",
                    "source",
                    "version",
                    "extra",
                    "pid-type",
                    "schema-type"
                }) {
            JSONObject changed = value(jar);
            switch (failure) {
                case "stopped" -> changed.put("state", "stopped");
                case "dead" -> changed.put("pid", Long.MAX_VALUE);
                case "start" -> changed.put("processStarted", "2000-01-01T00:00:00Z");
                case "executable" -> changed.put("executableSha256", "0".repeat(64));
                case "artifact" -> changed.put("artifactSha256", "0".repeat(64));
                case "source" -> changed.put("source", "b".repeat(40));
                case "version" -> changed.put("version", "0.9.0");
                case "extra" -> changed.put("arguments", "must not be accepted");
                case "pid-type" -> changed.put("pid", Long.toString(ProcessHandle.current().pid()));
                case "schema-type" -> changed.put("schema", "1");
                default -> throw new AssertionError(failure);
            }
            Files.writeString(receipt, changed.toString());
            assertThrows(
                    Exception.class, () -> LoadedIdentity.verify(receipt, jar, SOURCE), failure);
        }
    }

    @Test
    void boundedReadsRefuseSymlinkOversizeAndIncompleteJson() throws Exception {
        Path jar = artifact();
        Path receipt = temp.resolve("runtime-identity.json");
        for (String bytes : new String[] {"{", "x".repeat(4097), "{}"}) {
            Files.writeString(receipt, bytes);
            assertThrows(Exception.class, () -> LoadedIdentity.verify(receipt, jar, SOURCE));
        }
        Files.delete(receipt);
        Files.createSymbolicLink(receipt, jar);
        assertThrows(Exception.class, () -> LoadedIdentity.verify(receipt, jar, SOURCE));
    }

    @Test
    void receiptPublicationPreservesOtherStateAndRefusesSymlink() throws Exception {
        try (LocalState state = new LocalState(temp)) {
            Path preferences = temp.resolve("preferences.properties");
            Files.writeString(preferences, "unchanged");
            Path external = temp.resolve("unrelated");
            Files.writeString(external, "preserved");
            Files.createSymbolicLink(temp.resolve("runtime-identity.json"), external);
            assertThrows(Exception.class, () -> state.runtimeIdentity("{}"));
            assertEquals("preserved", Files.readString(external));
            assertEquals("unchanged", Files.readString(preferences));
        }
    }

    @Test
    void unknownPriorReceiptIsPreserved() throws Exception {
        Path receipt = temp.resolve("runtime-identity.json");
        Files.writeString(receipt, "unrecognized retained evidence");
        try (LocalState state = new LocalState(temp)) {
            assertThrows(Exception.class, () -> state.runtimeIdentity("{}"));
        }
        assertEquals("unrecognized retained evidence", Files.readString(receipt));
    }
}
