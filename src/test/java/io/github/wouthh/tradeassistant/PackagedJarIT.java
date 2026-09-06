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
    final Path jar = Path.of("target/g-earth-trade-assistant-0.1.0.jar").toAbsolutePath();

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
                    "io.github.wouthh.tradeassistant.protocol.TradeAssistantExtension",
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
        try (ZipFile zip = new ZipFile("target/G-Earth-Trade-Assistant-0.1.0-extension.zip")) {
            var command = zip.getEntry("G-Earth-Trade-Assistant-0.1.0/command.txt");
            assertNotNull(command);
            String text =
                    new String(
                            zip.getInputStream(command).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains("{cookie}"));
            assertTrue(text.contains("jre"));
            assertNotNull(
                    zip.getEntry("G-Earth-Trade-Assistant-0.1.0/G-Earth-Trade-Assistant.jar"));
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
                    assertEquals("0.1.0", info.readString());
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
                    output.write(new HPacket(6).toBytes());
                }
                assertTrue(
                        process.waitFor(8, TimeUnit.SECONDS),
                        "Packaged process failed to shut down");
                assertEquals(0, process.exitValue());
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
