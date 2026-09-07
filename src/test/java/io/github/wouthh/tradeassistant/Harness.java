package io.github.wouthh.tradeassistant;

import static io.github.wouthh.tradeassistant.domain.Model.*;

import io.github.wouthh.tradeassistant.domain.*;
import io.github.wouthh.tradeassistant.runtime.SafetyGate;
import java.util.*;

final class Harness implements Scheduler, Transport {
    record Timer(long due, long serial, Runnable action) {}

    record Sent(String kind, Handle handle, ObjectId object, Target target) {}

    final PriorityQueue<Timer> timers =
            new PriorityQueue<>(
                    Comparator.comparingLong(Timer::due).thenComparingLong(Timer::serial));
    final List<Sent> sent = new ArrayList<>();
    final List<Snapshot> journal = new ArrayList<>();
    final SafetyGate gate = new SafetyGate();
    final ConversionEngine engine;
    long time, serial;
    boolean reject, reflect, cancelPlace;

    Harness() {
        engine = new ConversionEngine(this, this, gate::generation, journal::add, s -> {}, false);
        engine.connected(true);
        engine.on(new RoomReady(42));
    }

    public long now() {
        return time;
    }

    public void later(long delay, Runnable action) {
        timers.add(new Timer(time + delay, serial++, action));
    }

    void advance(long amount) {
        long end = time + amount;
        int budget = 10000;
        while (!timers.isEmpty() && timers.peek().due() <= end) {
            if (--budget == 0) throw new AssertionError("Unbounded scheduler");
            Timer t = timers.remove();
            time = t.due();
            t.action().run();
        }
        time = end;
    }

    public Submission place(Handle h, Target t, long permit) {
        if (cancelPlace) {
            cancelPlace = false;
            gate.invalidate();
        }
        return gate.submit(
                permit,
                () -> {
                    sent.add(new Sent("place", h, null, t));
                    if (reflect) engine.on(new Placement(h, t));
                    return reject ? Submission.UNKNOWN : Submission.SUBMITTED;
                });
    }

    public Submission redeem(ObjectId id, long permit) {
        return gate.submit(
                permit,
                () -> {
                    sent.add(new Sent("redeem", null, id, null));
                    return reject ? Submission.UNKNOWN : Submission.SUBMITTED;
                });
    }

    static final Target TARGET = new Target(3, 4, 0);

    void drop(int value) {
        Handle h = new Handle(-value);
        engine.on(new Placement(h, TARGET));
        engine.on(new StripRemoval(h));
        engine.on(new Added(h.expectedObject(), BRONZE, TARGET));
    }

    void confirm(Sent s) {
        if (s.kind().equals("place")) {
            engine.on(new StripRemoval(s.handle()));
            engine.on(new Added(s.handle().expectedObject(), BRONZE, s.target()));
        } else engine.on(new Removed(s.object()));
    }

    static Inventory inventory(int... ids) {
        List<Integer> handles = Arrays.stream(ids).map(i -> -i).boxed().toList();
        return new Inventory(
                List.of(
                        new Group(
                                handles.getFirst(),
                                handles,
                                0,
                                "S",
                                ids[0],
                                0,
                                0,
                                BRONZE,
                                1,
                                1,
                                "0,0,0")),
                100);
    }
}
