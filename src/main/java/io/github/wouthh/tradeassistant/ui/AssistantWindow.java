package io.github.wouthh.tradeassistant.ui;

import static io.github.wouthh.tradeassistant.domain.Model.*;

import io.github.wouthh.tradeassistant.domain.*;
import io.github.wouthh.tradeassistant.runtime.LocalState;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.function.Consumer;
import javax.swing.*;

public final class AssistantWindow {
    private final JFrame frame = new JFrame("G-Earth Trade Assistant");
    private final JComboBox<String> mode =
            new JComboBox<>(new String[] {"Auto-redeem my drops", "Convert N inventory items"});
    private final JSpinner quantity = new JSpinner(new SpinnerNumberModel(1, 1, 1000, 1));
    private final JSpinner pacing = new JSpinner(new SpinnerNumberModel(1500, 500, 60000, 500));
    private final JSpinner timeout =
            new JSpinner(new SpinnerNumberModel(15000, 5000, 120000, 1000));
    private final JCheckBox learn =
            new JCheckBox("Learn from my next successful bronze placement (included in N)", true);
    private final JButton start = new JButton("Arm / Start"),
            pause = new JButton("Pause"),
            stop = new JButton("Stop");
    private final JTextArea status = new JTextArea(12, 62);
    private Snapshot snapshot;

    public AssistantWindow(
            Consumer<Consumer<ConversionEngine>> command,
            Consumer<Boolean> cancel,
            LocalState store,
            String recoverySummary) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Swing construction requires EDT");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        cancel.accept(false);
                        frame.setVisible(false);
                    }
                });
        JPanel body = new JPanel();
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.setName("runtime-controls");
        start.setName("runtime-start");
        controls.add(start);
        controls.add(pause);
        controls.add(stop);
        body.add(controls);
        body.add(row("Mode", mode));
        body.add(
                new JLabel(
                        "Eligible type: Bronze coin only · other currency types are unverified"));
        body.add(row("Maximum items", quantity));
        body.add(learn);
        body.add(row("Minimum spacing (ms)", pacing));
        body.add(row("Outcome timeout (ms)", timeout));
        body.add(
                new JLabel(
                        "Conversion is irreversible. No claim of platform approval or guaranteed account safety."));
        JPanel purchase = new JPanel();
        purchase.setLayout(new BoxLayout(purchase, BoxLayout.Y_AXIS));
        purchase.setBorder(
                BorderFactory.createTitledBorder("Optional replacement purchases — unavailable"));
        JCheckBox buy = new JCheckBox("Buy replacement gold bars after conversion", false);
        buy.setEnabled(false);
        purchase.add(buy);
        purchase.add(
                new JLabel(
                        "Product: CF_50_goldbar · Unit cost: unverified · Total cost: unavailable"));
        purchase.add(row("Purchase count", new JSpinner(new SpinnerNumberModel(1, 1, 1000, 1))));
        purchase.add(
                row(
                        "Maximum spend (integer habloons)",
                        new JSpinner(new SpinnerNumberModel(0, 0, 1_000_000, 1))));
        purchase.add(
                new JLabel(
                        "Default budget: confirmed proceeds from this run only (currently unverified)."));
        purchase.add(
                new JLabel(
                        "Missing: price, quantity, purchase response, inventory delivery, balance reconciliation."));
        body.add(purchase);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        body.add(new JScrollPane(status));
        if (!recoverySummary.isEmpty()) {
            JTextArea recovery = new JTextArea(recoverySummary, 3, 62);
            recovery.setEditable(false);
            recovery.setLineWrap(true);
            body.add(new JScrollPane(recovery));
        }
        JButton ack = new JButton("Review / acknowledge recovery journal");
        body.add(ack);
        ack.addActionListener(
                e -> {
                    if (JOptionPane.showConfirmDialog(
                                    frame,
                                    "Review recorded IDs in your local journal first. Acknowledgement sends nothing and does not undo a conversion. Dismiss automatic reconciliation?",
                                    "Acknowledge local recovery",
                                    JOptionPane.OK_CANCEL_OPTION)
                            == JOptionPane.OK_OPTION) command.accept(ConversionEngine::acknowledge);
                });
        start.addActionListener(
                e -> {
                    if (snapshot != null && snapshot.state() == State.PAUSED) {
                        command.accept(ConversionEngine::resume);
                        return;
                    }
                    try {
                        quantity.commitEdit();
                        pacing.commitEdit();
                        timeout.commitEdit();
                        Config cfg =
                                new Config(
                                        mode.getSelectedIndex() == 0
                                                ? Mode.MANUAL_DROPS
                                                : Mode.INVENTORY,
                                        (int) quantity.getValue(),
                                        ((Number) pacing.getValue()).longValue(),
                                        ((Number) timeout.getValue()).longValue(),
                                        learn.isSelected());
                        command.accept(
                                engine -> {
                                    try {
                                        store.preferences(
                                                cfg.quantity(),
                                                cfg.pacingMillis(),
                                                cfg.timeoutMillis());
                                    } catch (IOException ex) {
                                        throw new IllegalStateException(
                                                "Could not save preferences; run was not started");
                                    }
                                    engine.start(cfg);
                                });
                    } catch (Exception ex) {
                        error("Enter valid finite quantities, pacing and timeout values.");
                    }
                });
        pause.addActionListener(e -> cancel.accept(true));
        stop.addActionListener(e -> cancel.accept(false));
        // Small preference reads run on the worker, then publish harmless values to Swing.
        command.accept(
                engine -> {
                    try {
                        var prefs = store.preferences();
                        int n = Integer.parseInt(prefs.getProperty("quantity", "1"));
                        int p = Integer.parseInt(prefs.getProperty("pacing", "1500"));
                        int t = Integer.parseInt(prefs.getProperty("timeout", "15000"));
                        new Config(Mode.MANUAL_DROPS, n, p, t, false);
                        SwingUtilities.invokeLater(
                                () -> {
                                    quantity.setValue(n);
                                    pacing.setValue(p);
                                    timeout.setValue(t);
                                });
                    } catch (IOException | RuntimeException ignored) {
                        /* Keep editable safe defaults. */
                    }
                });
        frame.setContentPane(body);
        frame.pack();
        frame.setMinimumSize(new Dimension(700, 680));
        frame.setLocationByPlatform(true);
    }

    private static JPanel row(String label, JComponent value) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.add(new JLabel(label));
        p.add(value);
        return p;
    }

    public void show(Snapshot s) {
        update(s);
        frame.setVisible(true);
        frame.toFront();
    }

    public void update(Snapshot s) {
        snapshot = s;
        start.setText(s.state() == State.PAUSED ? "Resume" : "Arm / Start");
        boolean running = s.state() == State.ARMED || s.state() == State.RUNNING;
        start.setEnabled(
                !running && s.connected() && s.roomId() > 0 && s.state() != State.UNCERTAIN);
        pause.setEnabled(running);
        stop.setEnabled(running || s.state() == State.PAUSED || s.queued() > 0);
        status.setText(
                "State: "
                        + s.state()
                        + " · Origins: "
                        + s.connected()
                        + " · Room: "
                        + (s.roomId() > 0 ? s.roomId() : "unknown")
                        + "\nEligible instances observed: "
                        + s.available()
                        + " (partial inventory; load pages manually)"
                        + "\nObserved: "
                        + s.observed()
                        + " · Queued: "
                        + s.queued()
                        + " · Placed: "
                        + s.placed()
                        + " · Redeemed/removal confirmed: "
                        + s.redeemed()
                        + "\nFailed: "
                        + s.failed()
                        + " · Uncertain: "
                        + s.uncertain()
                        + " · Purchases sent/received/spend: 0 / 0 / 0"
                        + "\nBalance observed: "
                        + (s.balance() == null ? "unknown" : s.balance())
                        + " · Proceeds credited by this run: unverified"
                        + "\nDestination: "
                        + (s.target() == null ? "learn from a successful placement" : s.target())
                        + "\nKnown unredeemed object IDs: "
                        + s.unredeemed()
                        + "\nPending inventory handles: "
                        + s.pendingHandles()
                        + "\n\n"
                        + s.message());
    }

    public void error(String message) {
        JOptionPane.showMessageDialog(
                frame, message, "Trade Assistant", JOptionPane.WARNING_MESSAGE);
    }

    public void dispose() {
        frame.dispose();
    }
}
