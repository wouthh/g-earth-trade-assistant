package io.github.wouthh.tradeassistant.protocol;

import static io.github.wouthh.tradeassistant.domain.Model.*;
import static io.github.wouthh.tradeassistant.protocol.OriginsCodec.Binding;

import gearth.extensions.Extension;
import gearth.extensions.ExtensionInfo;
import gearth.protocol.*;
import gearth.protocol.connection.HClient;
import io.github.wouthh.tradeassistant.domain.*;
import io.github.wouthh.tradeassistant.runtime.*;
import io.github.wouthh.tradeassistant.ui.AssistantWindow;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.*;

@ExtensionInfo(
        Title = "G-Earth Trade Assistant",
        Author = "Wout H.",
        Version = "0.1.2",
        Description = "Explicitly armed Origins bronze conversion; purchasing unavailable.")
public final class TradeAssistantExtension extends Extension implements AutoCloseable {
    private final SafetyGate gate = new SafetyGate();
    private final OriginsCodec codec = new OriginsCodec();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LocalState store;
    private final SerialRuntime runtime;
    private final ConversionEngine engine;
    private volatile PacketBindings bindings;
    private volatile Snapshot latest;
    private volatile AssistantWindow window;
    private final String recoverySummary;
    private LoadedIdentity loadedIdentity;

    public TradeAssistantExtension(String[] args) throws IOException {
        super(args);
        store = new LocalState(LocalState.defaultDirectory());
        recoverySummary = store.recoverySummary();
        runtime = new SerialRuntime(gate::invalidate);
        engine =
                new ConversionEngine(
                        new Transport() {
                            public Submission place(Handle h, Target t, long permit) {
                                return inject(
                                        Binding.PLACESTUFF, permit, b -> codec.placement(b, h, t));
                            }

                            public Submission redeem(ObjectId id, long permit) {
                                return inject(
                                        Binding.CONVERT_FURNI_TO_HABLOONS,
                                        permit,
                                        b -> codec.redemption(b, id));
                            }
                        },
                        runtime,
                        gate::generation,
                        store,
                        this::update,
                        store.needsReview());
        runtime.onFailure(engine::runtimeFailed);
        latest = engine.snapshot();
        onConnect(
                (host, port, version, identifier, client) -> {
                    gate.invalidate();
                    PacketBindings found = null;
                    if (client == HClient.SHOCKWAVE
                            && host != null
                            && host.matches("game-o(?:d|us|br|es)\\.habbo\\.com")) {
                        try {
                            found = new PacketBindings(getPacketInfoManager());
                        } catch (IllegalArgumentException ignored) {
                            /* Explicit unavailable state below. */
                        }
                    }
                    bindings = found;
                    boolean compatible = found != null;
                    runtime.execute(() -> engine.connected(compatible));
                });
        intercept(HMessage.Direction.TOSERVER, this::observe);
        intercept(HMessage.Direction.TOCLIENT, this::observe);
    }

    private Submission inject(
            Binding binding, long permit, java.util.function.IntFunction<HPacket> packet) {
        PacketBindings current = bindings;
        if (closed.get() || current == null) return Submission.CANCELLED;
        final HPacket composed;
        try {
            composed = packet.apply(current.id(binding));
        } catch (RuntimeException e) {
            return Submission.UNKNOWN;
        }
        return gate.submit(
                permit,
                () -> {
                    if (closed.get() || bindings != current) return Submission.CANCELLED;
                    try {
                        return sendToServer(composed) ? Submission.SUBMITTED : Submission.UNKNOWN;
                    } catch (RuntimeException e) {
                        return Submission.UNKNOWN;
                    }
                });
    }

    private void observe(HMessage message) {
        try {
            observeChecked(message);
        } catch (RuntimeException e) {
            gate.invalidate();
            runtime.execute(
                    () -> {
                        engine.on(new ContextLost());
                        engine.malformed();
                    });
        }
    }

    private void observeChecked(HMessage message) {
        if (closed.get()) return;
        PacketBindings current = bindings;
        if (current == null) return;
        boolean incoming = message.getDestination() == HMessage.Direction.TOCLIENT;
        HPacket p = message.getPacket();
        Binding b = current.lookup(incoming, p.headerId());
        if (b == null) return;
        if (message.isBlocked()) {
            // An incoming context change already happened on the hotel, even when another
            // extension prevents the client from seeing it. Its body cannot establish usable
            // context.
            if (incoming
                    && (b == Binding.ROOM_READY
                            || b == Binding.ROOM_RIGHTS
                            || b == Binding.ROOM_RIGHTS_2
                            || b == Binding.ROOM_RIGHTS_3)) {
                gate.invalidate();
                runtime.execute(() -> engine.on(new ContextLost()));
            }
            return;
        }
        if (p.getFormat()
                != (incoming ? HPacketFormat.WEDGIE_INCOMING : HPacketFormat.WEDGIE_OUTGOING)) {
            gate.invalidate();
            runtime.execute(
                    () -> {
                        engine.on(new ContextLost());
                        engine.malformed();
                    });
            return;
        }
        if (b == Binding.ROOM_READY
                || b == Binding.QUIT
                || b == Binding.GOTOFLAT
                || b == Binding.ROOM_RIGHTS
                || b == Binding.ROOM_RIGHTS_2
                || b == Binding.ROOM_RIGHTS_3
                || b == Binding.ADDSTRIPITEM
                || b == Binding.MOVESTUFF
                || b == Binding.PURCHASE_FROM_CATALOG
                || b == Binding.CONVERT_FURNI_TO_HABLOONS) gate.invalidate();
        byte[] bytes = p.toBytes();
        if (bytes.length > 1_000_000) {
            gate.invalidate();
            runtime.execute(
                    () -> {
                        engine.on(new ContextLost());
                        engine.malformed();
                    });
            return;
        }
        runtime.execute(
                () -> {
                    if (closed.get()) return;
                    try {
                        engine.on(codec.decode(b, bytes));
                    } catch (RuntimeException e) {
                        gate.invalidate();
                        engine.on(new ContextLost());
                        engine.malformed();
                    }
                });
    }

    @Override
    public void onEndConnection() {
        gate.invalidate();
        bindings = null;
        runtime.execute(engine::disconnected);
    }

    @Override
    public void initExtension() {
        gate.invalidate();
        runtime.execute(
                () -> {
                    if (closed.get()) return;
                    engine.on(new ContextLost());
                    if (loadedIdentity == null) {
                        try {
                            loadedIdentity =
                                    LoadedIdentity.start(store, TradeAssistantExtension.class);
                        } catch (LoadedIdentity.UnverifiedBuild ignored) {
                            System.err.println(
                                    "Loaded identity unverified for this development build.");
                        } catch (Exception ignored) {
                            System.err.println(
                                    "Loaded identity unavailable; verification remains pending.");
                        }
                    }
                });
        onClick();
    }

    @Override
    public void onClick() {
        if (Boolean.getBoolean("java.awt.headless") || closed.get()) return;
        SwingUtilities.invokeLater(
                () -> {
                    if (window == null)
                        window =
                                new AssistantWindow(
                                        this::command, this::cancel, store, recoverySummary);
                    window.show(latest);
                });
    }

    private void update(Snapshot snapshot) {
        latest = snapshot;
        AssistantWindow w = window;
        if (w != null) SwingUtilities.invokeLater(() -> w.update(snapshot));
    }

    private void command(Consumer<ConversionEngine> action) {
        long requestFence = gate.generation();
        runtime.execute(
                () -> {
                    if (requestFence != gate.generation()) return;
                    try {
                        action.accept(engine);
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        AssistantWindow w = window;
                        if (w != null) SwingUtilities.invokeLater(() -> w.error(e.getMessage()));
                    }
                });
    }

    private void cancel(boolean pause) {
        gate.invalidate();
        runtime.execute(
                () -> {
                    if (pause) engine.pause();
                    else engine.stop();
                });
    }

    @Override
    public void run() {
        try {
            super.run();
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        gate.invalidate();
        bindings = null;
        runtime.finish(
                () -> {
                    engine.disconnected();
                    try {
                        if (loadedIdentity != null) loadedIdentity.close();
                    } catch (IOException ignored) {
                        // A stale receipt cannot pass the live process verifier after exit.
                        System.err.println("Shutdown identity unavailable; verify process state.");
                    }
                    try {
                        store.close();
                    } catch (IOException ignored) {
                        /* Journal already reports failures. */
                    }
                });
        AssistantWindow w = window;
        if (w != null) SwingUtilities.invokeLater(w::dispose);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--verify-loaded")) {
            if (args.length != 4)
                throw new IllegalArgumentException(
                        "Expected receipt, artifact and source revision");
            try {
                System.out.println(
                        LoadedIdentity.verify(
                                java.nio.file.Path.of(args[1]),
                                java.nio.file.Path.of(args[2]),
                                args[3]));
            } catch (Exception ignored) {
                System.err.println("Loaded identity could not be verified.");
                System.exit(2);
            }
            return;
        }
        if (Arrays.asList(args).contains("--demo")) {
            OfflineDemo.main(args);
            return;
        }
        try (TradeAssistantExtension extension = new TradeAssistantExtension(args)) {
            extension.run();
        }
    }
}
