package io.github.wouthh.tradeassistant.domain;

import static io.github.wouthh.tradeassistant.domain.Model.*;

import java.util.*;

/** Observed pages only. Tombstones prevent stale refreshes from resurrecting used handles. */
public final class InventoryBook {
    public record Instance(Handle handle, int slot, long pageRevision) {}

    private record Observed(Group group, long revision) {}

    private final Map<Integer, Observed> slots = new HashMap<>();
    private final Set<Integer> removed = new HashSet<>();
    private long revision;

    public void page(Inventory page) {
        Map<Integer, Observed> replacement = new HashMap<>(slots);
        for (Group group : page.groups())
            replacement.put(group.slot(), new Observed(group, revision + 1));
        long handles =
                replacement.values().stream().mapToLong(o -> o.group().handles().size()).sum();
        if (replacement.size() > 10_000 || handles > 100_000)
            throw new IllegalStateException("Observed inventory limit reached");
        slots.clear();
        slots.putAll(replacement);
        revision++;
    }

    public void removed(Handle handle) {
        if (removed.size() >= 100_000 && !removed.contains(handle.value()))
            throw new IllegalStateException("Inventory history limit reached");
        removed.add(handle.value());
    }

    public List<Instance> available() {
        Map<Integer, Instance> instances = new TreeMap<>();
        Set<Integer> ambiguous = new HashSet<>();
        for (Observed observation : slots.values()) {
            Group g = observation.group();
            for (int h : g.handles()) {
                if (h >= 0 || removed.contains(h)) continue;
                if (!g.type().equals(BRONZE)
                        || !g.kind().equals("S")
                        || g.width() != 1
                        || g.length() != 1) {
                    ambiguous.add(h);
                    continue;
                }
                Instance previous =
                        instances.putIfAbsent(
                                h, new Instance(new Handle(h), g.slot(), observation.revision()));
                if (previous != null && previous.slot() != g.slot()) ambiguous.add(h);
            }
        }
        ambiguous.forEach(instances::remove);
        return List.copyOf(instances.values());
    }

    public boolean contains(Handle h) {
        return available().stream().anyMatch(i -> i.handle().equals(h));
    }

    public void clear() {
        slots.clear();
        removed.clear();
        revision = 0;
    }
}
