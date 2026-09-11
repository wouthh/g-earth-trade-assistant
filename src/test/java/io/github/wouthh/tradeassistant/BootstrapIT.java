package io.github.wouthh.tradeassistant;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapIT {
    @TempDir Path root;
    private static final String TYPE =
            "io/github/wouthh/tradeassistant/protocol/TradeAssistantExtension";
    private static final String BOOT =
            "io/github/wouthh/tradeassistant/runtime/SnapshotClassLoader";

    private Path fixture(String initializer, String body, boolean malformed) throws Exception {
        Path source = root.resolve(TYPE + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                "package io.github.wouthh.tradeassistant.protocol; public class TradeAssistantExtension {"
                        + initializer
                        + " public static void main(String[] args) {"
                        + body
                        + "} } class OmittedDependency { static void touch() {} }");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", root.toString(), source.toString()));
        Path jar = root.resolve("bootstrap-fixture.jar");
        try (JarFile built = new JarFile("target/g-earth-trade-assistant-0.1.2.jar");
                JarOutputStream out =
                        new JarOutputStream(Files.newOutputStream(jar), built.getManifest())) {
            for (var entry : java.util.Collections.list(built.entries())) {
                String name = entry.getName();
                if (!(name.startsWith(BOOT) && name.endsWith(".class"))
                        && !name.equals("META-INF/tradeassistant-build.properties")) continue;
                out.putNextEntry(new JarEntry(name));
                try (var input = built.getInputStream(entry)) {
                    input.transferTo(out);
                }
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry(TYPE + ".class"));
            out.write(
                    malformed
                            ? new byte[] {0, 1}
                            : Files.readAllBytes(root.resolve(TYPE + ".class")));
            out.closeEntry();
        }
        return jar;
    }

    private void launch(Path jar, int status, String message) throws Exception {
        Process child =
                new ProcessBuilder(
                                ProcessHandle.current().info().command().orElseThrow(),
                                "-jar",
                                jar.toString())
                        .redirectErrorStream(true)
                        .start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(status, child.exitValue());
            assertEquals(
                    message,
                    new String(child.getInputStream().readNBytes(4097), StandardCharsets.UTF_8)
                            .trim());
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    @Test
    void startsThroughSnapshotWithoutAnyApiOrOtherProductClasses() throws Exception {
        Path jar =
                fixture(
                        "",
                        "System.out.println(TradeAssistantExtension.class.getClassLoader().getClass().getName());",
                        false);
        launch(jar, 0, BOOT.replace('/', '.'));
    }

    @Test
    void rejectsMalformedBytecodeWithoutExceptionDetails() throws Exception {
        launch(fixture("", "", true), 2, "Extension archive could not be loaded safely.");
    }

    @Test
    void reportsInitializationFailureAsArchiveSetup() throws Exception {
        launch(
                fixture(
                        "static { if (true) throw new RuntimeException(\"synthetic hidden detail\"); }",
                        "",
                        false),
                2,
                "Extension archive could not be loaded safely.");
    }

    @Test
    void reportsInvokedRuntimeFailureSeparately() throws Exception {
        launch(
                fixture("", "throw new RuntimeException(\"synthetic hidden detail\");", false),
                3,
                "Extension runtime stopped unexpectedly.");
    }

    @Test
    void sanitizesNonfatalInitializerErrors() throws Exception {
        for (String error : new String[] {"AssertionError", "java.awt.AWTError"}) {
            launch(
                    fixture(
                            "static { if (true) throw new "
                                    + error
                                    + "(\"synthetic hidden detail\"); }",
                            "",
                            false),
                    2,
                    "Extension archive could not be loaded safely.");
        }
    }

    @Test
    void classifiesMissingLazyDependencyAsArchiveFailure() throws Exception {
        launch(
                fixture("", "OmittedDependency.touch();", false),
                2,
                "Extension archive could not be loaded safely.");
    }

    @Test
    void preservesFatalVmFailurePropagationInBothPhases() throws Exception {
        for (boolean initializing : new boolean[] {true, false}) {
            String fail = "throw new OutOfMemoryError(\"synthetic fatal VM failure\");";
            Path jar =
                    fixture(
                            initializing ? "static { if (true) { " + fail + " } }" : "",
                            initializing ? "" : fail,
                            false);
            Process child =
                    new ProcessBuilder(
                                    ProcessHandle.current().info().command().orElseThrow(),
                                    "-jar",
                                    jar.toString())
                            .redirectErrorStream(true)
                            .start();
            try {
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
                assertEquals(1, child.exitValue());
                String output =
                        new String(child.getInputStream().readNBytes(4097), StandardCharsets.UTF_8);
                assertEquals("Extension stopped after a fatal runtime error.", output.trim());
            } finally {
                if (child.isAlive()) child.destroyForcibly();
            }
        }
    }

    @Test
    void preservesSilentThreadTerminationInBothPhases() throws Exception {
        for (boolean initializing : new boolean[] {true, false}) {
            String fail = "throw new ThreadDeath();";
            launch(
                    fixture(
                            initializing ? "static { if (true) { " + fail + " } }" : "",
                            initializing ? "" : fail,
                            false),
                    0,
                    "");
        }
    }
}
