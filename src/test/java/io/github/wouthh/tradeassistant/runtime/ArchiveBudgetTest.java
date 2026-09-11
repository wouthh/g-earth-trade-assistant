package io.github.wouthh.tradeassistant.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveBudgetTest {
    @TempDir Path root;
    private static final int BUDGET = 64 * 1024 * 1024;
    private static final String SOURCE = "a".repeat(40);

    private Map<String, byte[]> metadata() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put(
                "META-INF/tradeassistant-build.properties",
                ("source=" + SOURCE + "\nversion=0.1.3\n").getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private Path archive(String name, int payloadSize, boolean stored) throws Exception {
        Path path = root.resolve(name + ".jar");
        byte[] block = new byte[8192];
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var value : metadata().entrySet()) {
                output.putNextEntry(new JarEntry(value.getKey()));
                output.write(value.getValue());
                output.closeEntry();
            }
            JarEntry payload = new JarEntry("fixture/padding.bin");
            if (stored) {
                CRC32 crc = new CRC32();
                for (int left = payloadSize; left > 0; left -= Math.min(left, block.length))
                    crc.update(block, 0, Math.min(left, block.length));
                payload.setMethod(JarEntry.STORED);
                payload.setSize(payloadSize);
                payload.setCrc(crc.getValue());
            }
            output.putNextEntry(payload);
            for (int left = payloadSize; left > 0; left -= Math.min(left, block.length))
                output.write(block, 0, Math.min(left, block.length));
            output.closeEntry();
        }
        return path;
    }

    @Test
    void storedArchiveAndIdentityProbeUseTheDocumentedBinaryMegabyteLimit() throws Exception {
        int overhead = Math.toIntExact(Files.size(archive("empty", 0, true)));
        Path atLimit = archive("at-limit", BUDGET - overhead, true);
        assertEquals(BUDGET, Files.size(atLimit));
        var snapshot = new SnapshotClassLoader(atLimit);
        assertEquals(snapshot.artifactSha256(), LoadedIdentity.digest(atLimit));
        var process = ProcessHandle.current();
        Path receipt = root.resolve("identity.json");
        Files.writeString(
                receipt,
                new JSONObject()
                        .put("schema", 1)
                        .put("component", "g-earth-trade-assistant")
                        .put("version", "0.1.3")
                        .put("source", SOURCE)
                        .put("artifactSha256", snapshot.artifactSha256())
                        .put(
                                "executableSha256",
                                LoadedIdentity.digest(
                                        Path.of(process.info().command().orElseThrow())))
                        .put("pid", process.pid())
                        .put(
                                "processStarted",
                                process.info().startInstant().orElseThrow().toString())
                        .put("state", "loaded")
                        .toString());
        assertTrue(LoadedIdentity.verify(receipt, atLimit, SOURCE).getBoolean("loaded"));

        Path aboveLimit = archive("above-limit", BUDGET - overhead + 1, true);
        assertEquals(BUDGET + 1L, Files.size(aboveLimit));
        assertEquals(
                "Snapshot archive exceeds its bound",
                assertThrows(IOException.class, () -> new SnapshotClassLoader(aboveLimit))
                        .getMessage());
        assertThrows(IOException.class, () -> LoadedIdentity.digest(aboveLimit));
        assertThrows(IOException.class, () -> LoadedIdentity.verify(receipt, aboveLimit, SOURCE));
    }

    @Test
    void expandedArchiveAcceptsTheExactLimitAndRejectsOneAdditionalByte() throws Exception {
        int metadataSize = metadata().values().stream().mapToInt(bytes -> bytes.length).sum();
        Path atLimit = archive("expanded-at-limit", BUDGET - metadataSize, false);
        assertTrue(Files.size(atLimit) < 100_000);
        assertDoesNotThrow(() -> new SnapshotClassLoader(atLimit));
        Path aboveLimit = archive("expanded-above-limit", BUDGET - metadataSize + 1, false);
        assertTrue(Files.size(aboveLimit) < 100_000);
        assertEquals(
                "Snapshot expansion exceeds its bound",
                assertThrows(IOException.class, () -> new SnapshotClassLoader(aboveLimit))
                        .getMessage());
    }
}
