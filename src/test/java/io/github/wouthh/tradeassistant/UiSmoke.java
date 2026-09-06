package io.github.wouthh.tradeassistant;

import io.github.wouthh.tradeassistant.runtime.LocalState;
import io.github.wouthh.tradeassistant.ui.AssistantWindow;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Optional local visual verification of an isolated window; never connects to a host. */
public final class UiSmoke {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("trade-assistant-ui-");
        Harness h = new Harness();
        try (LocalState store = new LocalState(directory)) {
            SwingUtilities.invokeAndWait(
                    () -> {
                        AssistantWindow window =
                                new AssistantWindow(
                                        action -> {},
                                        pause -> {},
                                        store,
                                        "Offline preview: synthetic room; no host connection.");
                        try {
                            window.show(h.engine.snapshot());
                            JFrame frame = (JFrame) Frame.getFrames()[0];
                            frame.setTitle("Offline UI verification — no hotel connection");
                            JComponent controls = find(frame, "runtime-controls");
                            JComponent start = find(frame, "runtime-start");
                            if (controls == null
                                    || start == null
                                    || !start.isShowing()
                                    || !start.isEnabled())
                                throw new AssertionError("Primary controls inaccessible");
                            BufferedImage image =
                                    new BufferedImage(
                                            frame.getWidth(),
                                            frame.getHeight(),
                                            BufferedImage.TYPE_INT_RGB);
                            Graphics2D graphics = image.createGraphics();
                            frame.paint(graphics);
                            graphics.dispose();
                            ImageIO.write(image, "png", Path.of(".build/ui-smoke.png").toFile());
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        } finally {
                            window.dispose();
                        }
                    });
        }
        System.out.println(
                "Offline Swing window and visible Start controls verified; preview saved locally.");
    }

    private static JComponent find(Container root, String name) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComponent j && name.equals(j.getName())) return j;
            if (component instanceof Container c) {
                JComponent found = find(c, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
