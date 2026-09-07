package io.github.wouthh.tradeassistant.runtime;

import static io.github.wouthh.tradeassistant.domain.Model.*;

import io.github.wouthh.tradeassistant.domain.*;
import java.util.*;

/** Network-free, no files, no native host. Also exercises the packaged entry point. */
public final class OfflineDemo {
    private OfflineDemo() {}

    public static void main(String[] args) {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        List<String> sent = new ArrayList<>();
        Scheduler clock =
                new Scheduler() {
                    public long now() {
                        return 0;
                    }

                    public void later(long delay, Runnable action) {
                        if (delay == 0) tasks.add(action);
                    }
                };
        Transport transport =
                new Transport() {
                    public Submission place(Handle h, Target t, long p) {
                        sent.add("placement");
                        return Submission.SUBMITTED;
                    }

                    public Submission redeem(ObjectId id, long p) {
                        sent.add("redemption");
                        return Submission.SUBMITTED;
                    }
                };
        ConversionEngine engine =
                new ConversionEngine(transport, clock, () -> 0, s -> {}, s -> {}, false);
        engine.connected(true);
        engine.on(new RoomReady(42));
        engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        Handle h = new Handle(-120045);
        Target target = new Target(3, 4, 0);
        engine.on(new Placement(h, target));
        engine.on(new StripRemoval(h));
        engine.on(new Added(h.expectedObject(), BRONZE, target));
        while (!tasks.isEmpty()) tasks.remove().run();
        engine.on(new Removed(h.expectedObject()));
        if (engine.snapshot().state() != State.COMPLETE || sent.size() != 1)
            throw new IllegalStateException("Offline demo failed");
        System.out.println(
                "OFFLINE DEMO PASS: one synthetic bronze redemption reconciled; no network, account or files used.");
    }
}
