package io.github.wouthh.tradeassistant;

import static org.junit.jupiter.api.Assertions.*;

import gearth.protocol.*;
import io.github.wouthh.tradeassistant.protocol.OriginsCodec;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedJarIT {
    @TempDir Path temp;
    final Path jar = Path.of("target/g-earth-trade-assistant-0.1.4.jar").toAbsolutePath();

    @Test
    void replacedPathCannotBeAttestedAsTheExecutingSnapshot() throws Exception {
        Path installed = Files.createDirectory(temp.resolve("installed"));
        Path a = installed.resolve("a.jar");
        Path b = installed.resolve("b.jar");
        for (Path destination : new Path[] {a, b}) {
            String source = (destination.equals(a) ? "a" : "b").repeat(40);
            try (var original = new JarFile(jar.toFile());
                    var output =
                            new java.util.jar.JarOutputStream(Files.newOutputStream(destination))) {
                for (var entry : java.util.Collections.list(original.entries())) {
                    output.putNextEntry(new java.util.jar.JarEntry(entry.getName()));
                    if (entry.getName().equals("META-INF/tradeassistant-build.properties"))
                        output.write(
                                ("source=" + source + "\nversion=0.1.4\n")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    else
                        try (var input = original.getInputStream(entry)) {
                            input.transferTo(output);
                        }
                    output.closeEntry();
                }
            }
        }
        Path retained = Files.copy(a, installed.resolve("retained-a.jar"));
        var loader = new io.github.wouthh.tradeassistant.runtime.SnapshotClassLoader(a);
        Class<?> anchor =
                loader.loadClass(
                        "io.github.wouthh.tradeassistant.protocol.TradeAssistantExtension");
        // The archive pathname changes after the runtime class has been loaded.
        Files.move(b, a, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Class<?> stateType = loader.loadClass("io.github.wouthh.tradeassistant.runtime.LocalState");
        Class<?> identityType =
                loader.loadClass("io.github.wouthh.tradeassistant.runtime.LoadedIdentity");
        String previous = System.getProperty("tradeassistant.stateDir");
        Path stateDirectory = temp.resolve("state");
        System.setProperty("tradeassistant.stateDir", stateDirectory.toString());
        try (var state =
                (AutoCloseable) stateType.getConstructor(Path.class).newInstance(stateDirectory)) {
            try (var identity =
                    (AutoCloseable)
                            identityType
                                    .getMethod("start", stateType, Class.class)
                                    .invoke(null, state, anchor)) {
                Path receipt = stateDirectory.resolve("runtime-identity.json");
                var evidence =
                        io.github.wouthh.tradeassistant.runtime.LoadedIdentity.verify(
                                receipt, retained, "a".repeat(40));
                assertTrue(evidence.getBoolean("loaded"));
                assertThrows(
                        Exception.class,
                        () ->
                                io.github.wouthh.tradeassistant.runtime.LoadedIdentity.verify(
                                        receipt, a, "b".repeat(40)));
                assertFalse(Files.readString(receipt).contains(temp.toString()));
            }
            assertEquals(
                    "stopped",
                    new org.json.JSONObject(
                                    Files.readString(
                                            stateDirectory.resolve("runtime-identity.json")))
                            .getString("state"));
        } finally {
            if (previous == null) System.clearProperty("tradeassistant.stateDir");
            else System.setProperty("tradeassistant.stateDir", previous);
        }
    }

    Process launch(String... extra) throws IOException {
        var args = new java.util.ArrayList<String>();
        args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        args.add("-Djava.awt.headless=true");
        args.add("-Dtradeassistant.stateDir=" + temp.resolve("state"));
        args.add("-jar");
        args.add(jar.toString());
        args.addAll(java.util.List.of(extra));
        return new ProcessBuilder(args)
                .redirectErrorStream(true)
                .redirectOutput(temp.resolve("process.log").toFile())
                .start();
    }

    @Test
    void metadataAndNetworkFreeDemo() throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            assertEquals(
                    "io.github.wouthh.tradeassistant.runtime.SnapshotClassLoader",
                    archive.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(archive.getEntry("META-INF/LICENSE"));
            assertNotNull(archive.getEntry("META-INF/THIRD-PARTY-NOTICES.md"));
            assertNotNull(archive.getEntry("META-INF/APACHE-LICENSE-2.0.txt"));
            assertNotNull(archive.getEntry("META-INF/licenses/G-Earth-MIT.txt"));
            assertNotNull(archive.getEntry("META-INF/licenses/SLF4J-LICENSE.txt"));
            assertNotNull(archive.getEntry("META-INF/licenses/JSON-java-LICENSE.txt"));
        }
        Process process = launch("--demo");
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertTrue(Files.readString(temp.resolve("process.log")).contains("OFFLINE DEMO PASS"));
            assertFalse(Files.exists(temp.resolve("state")));
        } finally {
            process.destroyForcibly();
        }
        try (ZipFile zip = new ZipFile("target/G-Earth-Trade-Assistant-0.1.4-extension.zip")) {
            var command = zip.getEntry("G-Earth-Trade-Assistant-0.1.4/command.txt");
            assertNotNull(command);
            String text =
                    new String(
                            zip.getInputStream(command).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains("{cookie}"));
            assertTrue(text.contains("jre"));
            assertNotNull(
                    zip.getEntry(
                            "G-Earth-Trade-Assistant-0.1.4/extension/G-Earth-Trade-Assistant.jar"));
        }
    }

    @Test
    void extractedFolderLaunchesFromTheHostsExtensionWorkingDirectory() throws Exception {
        Path extracted = temp.resolve("extracted");
        try (ZipFile zip = new ZipFile("target/G-Earth-Trade-Assistant-0.1.4-extension.zip")) {
            for (var entry : java.util.Collections.list(zip.entries())) {
                Path destination = extracted.resolve(entry.getName()).normalize();
                assertTrue(destination.startsWith(extracted));
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    try (var input = zip.getInputStream(entry)) {
                        Files.copy(input, destination);
                    }
                }
            }
        }
        Path folder = extracted.resolve("G-Earth-Trade-Assistant-0.1.4");
        var command = new org.json.JSONArray(Files.readString(folder.resolve("command.txt")));
        assertEquals("C:\\G-Earth\\jre\\bin\\java.exe", command.getString(0));
        var args = new java.util.ArrayList<String>();
        args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        args.add("-Djava.awt.headless=true");
        args.add("-Dtradeassistant.stateDir=" + temp.resolve("state"));
        for (int i = 1; i < command.length(); i++) {
            args.add(
                    command.getString(i)
                            .replace("{port}", "1")
                            .replace("{filename}", "fixture")
                            .replace("{cookie}", "synthetic-cookie"));
        }
        args.add("--demo");
        Process process =
                new ProcessBuilder(args)
                        .directory(folder.resolve("extension").toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(temp.resolve("folder-demo.log").toFile())
                        .start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(temp.resolve("folder-demo.log")));
            assertTrue(
                    Files.readString(temp.resolve("folder-demo.log"))
                            .contains("OFFLINE DEMO PASS"));
            assertFalse(Files.exists(temp.resolve("state")));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void authenticatesWithFakeHostAndReturnsPacketsUnchangedWhileDisarmed() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(8000);
            Process process =
                    launch(
                            "-p",
                            Integer.toString(server.getLocalPort()),
                            "-f",
                            "fixture",
                            "-c",
                            "synthetic-cookie");
            try {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(8000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    OutputStream output = socket.getOutputStream();
                    output.write(new HPacket(2).toBytes());
                    HPacket info = read(input);
                    assertEquals(1, info.headerId());
                    assertEquals("G-Earth Trade Assistant", info.readString());
                    assertEquals("Wout H.", info.readString());
                    assertEquals("0.1.4", info.readString());
                    info.readString();
                    assertTrue(info.readBoolean());
                    assertTrue(info.readBoolean());
                    assertEquals("fixture", info.readString());
                    assertEquals("synthetic-cookie", info.readString());
                    output.write(new HPacket(7, false, "Fake G-Earth", "test", 0).toBytes());
                    output.write(
                            new HPacket(
                                            5,
                                            "game-ous.habbo.com",
                                            40001,
                                            "fixture",
                                            "offline",
                                            "SHOCKWAVE",
                                            0)
                                    .toBytes());
                    HPacket room = new OriginsCodec().representation("AEmodel_a 42", true);
                    HMessage message = new HMessage(room, HMessage.Direction.TOCLIENT, 7);
                    output.write(
                            new HPacket(3)
                                    .appendLongString(message.stringify())
                                    .appendInt(HPacketFormat.WEDGIE_INCOMING.getId())
                                    .toBytes());
                    boolean returned = false;
                    for (int i = 0; i < 5; i++) {
                        HPacket response = read(input);
                        assertNotEquals(
                                4, response.headerId(), "Disarmed extension sent a hotel packet");
                        if (response.headerId() == 2) {
                            assertEquals(message.stringify(), response.readLongString());
                            assertEquals(1, response.readInteger());
                            returned = true;
                            break;
                        }
                    }
                    assertTrue(returned);
                    java.util.Properties build = new java.util.Properties();
                    try (JarFile archive = new JarFile(jar.toFile());
                            var provenance =
                                    archive.getInputStream(
                                            archive.getEntry(
                                                    "META-INF/tradeassistant-build.properties"))) {
                        build.load(provenance);
                    }
                    Path identity = temp.resolve("state/runtime-identity.json");
                    if (build.getProperty("source").matches("[0-9a-f]{40}")) {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                        while (!Files.exists(identity) && System.nanoTime() < deadline)
                            Thread.sleep(10);
                        assertTrue(
                                Files.exists(identity),
                                "Verified packaged runtime did not publish identity");
                        var evidence =
                                io.github.wouthh.tradeassistant.runtime.LoadedIdentity.verify(
                                        identity, jar, build.getProperty("source"));
                        assertTrue(evidence.getBoolean("loaded"));
                        Process verifier =
                                new ProcessBuilder(
                                                Path.of(
                                                                System.getProperty("java.home"),
                                                                "bin",
                                                                "java")
                                                        .toString(),
                                                "-jar",
                                                jar.toString(),
                                                "--verify-loaded",
                                                identity.toString(),
                                                jar.toString(),
                                                build.getProperty("source"))
                                        .redirectErrorStream(true)
                                        .redirectOutput(temp.resolve("identity.log").toFile())
                                        .start();
                        try {
                            assertTrue(verifier.waitFor(5, TimeUnit.SECONDS));
                            assertEquals(
                                    0,
                                    verifier.exitValue(),
                                    Files.readString(temp.resolve("identity.log")));
                            assertTrue(
                                    new org.json.JSONObject(
                                                    Files.readString(temp.resolve("identity.log")))
                                            .getBoolean("loaded"));
                        } finally {
                            verifier.destroyForcibly();
                        }
                        String receipt = Files.readString(identity);
                        assertFalse(receipt.contains("synthetic-cookie"));
                        assertFalse(receipt.contains(temp.toString()));
                    } else {
                        assertEquals("unverified", build.getProperty("source"));
                        assertFalse(
                                Files.exists(identity),
                                "Development build claimed verified provenance");
                    }
                    output.write(new HPacket(6).toBytes());
                }
                assertTrue(
                        process.waitFor(8, TimeUnit.SECONDS),
                        "Packaged process failed to shut down");
                assertEquals(0, process.exitValue());
                Path identity = temp.resolve("state/runtime-identity.json");
                if (Files.exists(identity)) {
                    var receipt = new org.json.JSONObject(Files.readString(identity));
                    assertEquals("stopped", receipt.getString("state"));
                    assertThrows(
                            Exception.class,
                            () ->
                                    io.github.wouthh.tradeassistant.runtime.LoadedIdentity.verify(
                                            identity, jar, receipt.getString("source")));
                }
            } finally {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void truncatedHostFrameTerminatesWithoutBusyLoop() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(8000);
            Process process = launch("-p", Integer.toString(server.getLocalPort()));
            try {
                try (Socket socket = server.accept()) {
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    output.writeInt(20);
                    output.writeByte(0);
                }
                assertTrue(
                        process.waitFor(8, TimeUnit.SECONDS),
                        "Partial frame caused a stuck API read loop");
            } finally {
                process.destroyForcibly();
            }
        }
    }

    private HPacket read(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 2 || size > 1_000_000)
            throw new IOException("Invalid fake-host response length");
        byte[] bytes = new byte[size + 4];
        input.readFully(bytes, 4, size);
        HPacket p = new HPacket(bytes);
        p.fixLength();
        return p;
    }
}
