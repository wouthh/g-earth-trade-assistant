package io.github.wouthh.tradeassistant.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Model {
    private Model() {}

    public static final String BRONZE = "CF_1_coin_bronze";

    public record Handle(int value) {
        public Handle {
            if (value >= 0 || value == Integer.MIN_VALUE)
                throw new IllegalArgumentException("Expected negative floor inventory handle");
        }

        public ObjectId expectedObject() {
            return new ObjectId(-value);
        }
    }

    public record ObjectId(int value) {
        public ObjectId {
            if (value <= 0) throw new IllegalArgumentException("Expected positive room object ID");
        }
    }

    public record Target(int x, int y, int rotation) {
        public Target {
            if (x < 0 || y < 0 || x > 255 || y > 255 || rotation < 0 || rotation > 7)
                throw new IllegalArgumentException("Invalid placement target");
        }
    }

    public record Group(
            int representative,
            List<Integer> handles,
            int slot,
            String kind,
            int objectId,
            int unknown1,
            int unknown2,
            String type,
            int width,
            int length,
            String data) {
        public Group {
            handles = List.copyOf(handles);
        }
    }

    public sealed interface Event {}

    public record Inventory(List<Group> groups, int trailer) implements Event {
        public Inventory {
            groups = List.copyOf(groups);
        }
    }

    public record InventoryRequest(String action) implements Event {}

    public record Placement(Handle handle, Target target) implements Event {}

    public record StripRemoval(Handle handle) implements Event {}

    public record Added(ObjectId id, String type, Target target) implements Event {}

    public record Removed(ObjectId id) implements Event {}

    public record Balance(long absolute) implements Event {}

    public record ExternalRedemption(ObjectId id) implements Event {}

    public record Conflict() implements Event {}

    public record RoomReady(int id) implements Event {}

    public record ContextLost() implements Event {}

    public record PermissionsChanged() implements Event {}

    public enum Mode {
        MANUAL_DROPS,
        INVENTORY
    }

    public enum State {
        DISARMED,
        ARMED,
        RUNNING,
        PAUSED,
        STOPPED,
        COMPLETE,
        UNCERTAIN
    }

    public record Config(
            Mode mode, int quantity, long pacingMillis, long timeoutMillis, boolean learnTarget) {
        public Config {
            if (mode == null
                    || quantity < 1
                    || quantity > 1000
                    || pacingMillis < 500
                    || pacingMillis > 60000
                    || timeoutMillis < 5000
                    || timeoutMillis > 120000)
                throw new IllegalArgumentException("Invalid run limits");
        }

        public static Config defaults(Mode mode, int quantity, boolean learn) {
            return new Config(mode, quantity, 1500, 15000, learn);
        }
    }

    public record Snapshot(
            State state,
            boolean connected,
            int roomId,
            int available,
            int observed,
            int queued,
            int placed,
            int redeemed,
            int failed,
            int uncertain,
            Long balance,
            Target target,
            Set<Integer> unredeemed,
            Set<Integer> pendingHandles,
            Map<Integer, String> operations,
            String message) {
        public Snapshot {
            unredeemed = Set.copyOf(unredeemed);
            pendingHandles = Set.copyOf(pendingHandles);
            operations = Map.copyOf(operations);
        }
    }

    public enum Submission {
        SUBMITTED,
        UNKNOWN
    }

    public interface Transport {
        Submission place(Handle handle, Target target, long permit);

        Submission redeem(ObjectId id, long permit);
    }

    public interface Scheduler {
        long now();

        void later(long delayMillis, Runnable action);
    }

    public interface Journal {
        void save(Snapshot state) throws java.io.IOException;
    }
}
