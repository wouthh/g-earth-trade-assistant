package io.github.wouthh.tradeassistant.protocol;

import gearth.encoding.VL64Encoding;
import gearth.protocol.HPacket;
import gearth.protocol.HPacketFormat;
import gearth.protocol.packethandler.shockwave.packets.ShockPacketOutgoing;
import io.github.wouthh.tradeassistant.domain.Model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict, capture-profile decoding. Field labels do not imply undocumented semantics. */
public final class OriginsCodec {
    public enum Binding {
        GETSTRIP(false, 65),
        STRIPINFO_2(true, 140),
        PLACESTUFF(false, 90),
        REMOVESTRIPITEM(true, 99),
        ACTIVEOBJECT_ADD(true, 93),
        CONVERT_FURNI_TO_HABLOONS(false, 1245),
        ACTIVEOBJECT_REMOVE(true, 94),
        HABLOON_BALANCE(true, 1249),
        PURCHASE_FROM_CATALOG(false, 100),
        ROOM_READY(true, 69),
        QUIT(false, 53),
        GOTOFLAT(false, 59),
        ROOM_RIGHTS(true, 42),
        ROOM_RIGHTS_2(true, 43),
        ROOM_RIGHTS_3(true, 47),
        ADDSTRIPITEM(false, 67),
        MOVESTUFF(false, 73);
        public final boolean incoming;
        public final int header;

        Binding(boolean incoming, int header) {
            this.incoming = incoming;
            this.header = header;
        }
    }

    public HPacket representation(String text, boolean incoming) {
        if (text.length() > 1_000_000) throw bad();
        HPacket p =
                (incoming ? HPacketFormat.WEDGIE_INCOMING : HPacketFormat.WEDGIE_OUTGOING)
                        .createPacket(text);
        new Cursor(p.toBytes());
        return p;
    }

    public Event decode(Binding b, byte[] bytes) {
        Cursor c = new Cursor(bytes);
        Event event =
                switch (b) {
                    case PLACESTUFF ->
                            new Placement(
                                    new Handle(c.integer()),
                                    new Target(c.integer(), c.integer(), c.integer()));
                    case REMOVESTRIPITEM -> {
                        int handle = c.integer();
                        // Wall furniture removals are valid unrelated inventory traffic.
                        yield handle < 0 ? new StripRemoval(new Handle(handle)) : null;
                    }
                    case CONVERT_FURNI_TO_HABLOONS ->
                            new ExternalRedemption(new ObjectId(c.integer()));
                    case ACTIVEOBJECT_REMOVE -> new Removed(new ObjectId(c.decimal(c.rest())));
                    case HABLOON_BALANCE -> {
                        int v = c.integer();
                        if (v < 0) throw bad();
                        yield new Balance(v);
                    }
                    case GETSTRIP -> {
                        String action = c.rest();
                        if (!Set.of("new", "next", "update").contains(action)) throw bad();
                        yield new InventoryRequest(action);
                    }
                    case STRIPINFO_2 -> inventory(c);
                    case ACTIVEOBJECT_ADD -> added(c);
                    case ROOM_READY -> {
                        String[] fields = c.rest().split(" ", -1);
                        if (fields.length != 2 || !fields[0].matches("[A-Za-z0-9_*-]{1,64}"))
                            throw bad();
                        yield new RoomReady(c.decimal(fields[1]));
                    }
                    // Only the mapped transition identity is used; no ownership/rights value is
                    // inferred.
                    case QUIT, GOTOFLAT -> {
                        c.rest();
                        yield new ContextLost();
                    }
                    case ROOM_RIGHTS, ROOM_RIGHTS_2, ROOM_RIGHTS_3 -> {
                        c.rest();
                        yield new PermissionsChanged();
                    }
                    case ADDSTRIPITEM, MOVESTUFF -> {
                        c.rest();
                        yield new Conflict();
                    }
                    case PURCHASE_FROM_CATALOG -> {
                        c.rest();
                        yield new Conflict();
                    }
                };
        c.end();
        return event;
    }

    private Inventory inventory(Cursor c) {
        int count = c.count(1000);
        List<Group> groups = new ArrayList<>();
        Set<Integer> all = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int rep = c.integer();
            if (rep == 0 || rep == Integer.MIN_VALUE) throw bad();
            int extra = c.count(100_000);
            List<Integer> handles = new ArrayList<>();
            handles.add(rep);
            for (int j = 0; j < extra; j++) handles.add(c.integer());
            int slot = c.count(1_000_000);
            String kind = c.string();
            if (!Set.of("S", "I").contains(kind) || !slots.add(slot)) throw bad();
            int id = c.integer(), u1 = c.integer(), u2 = c.integer();
            String type = c.string();
            if (id <= 0
                    || id != Math.abs(rep)
                    || u1 != 0
                    || u2 != 0
                    || !type.matches("[A-Za-z0-9_.*-]{1,128}")) throw bad();
            int width = 0, length = 0;
            if (kind.equals("S")) {
                width = c.count(255);
                length = c.count(255);
                if (width == 0 || length == 0) throw bad();
            }
            String data = c.string();
            for (int h : handles)
                if (h == 0 || h == Integer.MIN_VALUE || (h < 0) != kind.equals("S") || !all.add(h))
                    throw bad();
            groups.add(new Group(rep, handles, slot, kind, id, u1, u2, type, width, length, data));
            if (all.size() > 100_000) throw bad();
        }
        return new Inventory(groups, c.count(Integer.MAX_VALUE));
    }

    private Added added(Cursor c) {
        ObjectId id = new ObjectId(c.decimal(c.string()));
        c.integer(); // Present in the capture; not treated as authoritative ownership.
        String type = c.string();
        int x = c.integer(), y = c.integer(), w = c.count(255), l = c.count(255), rot = c.integer();
        if (w == 0 || l == 0) throw bad();
        String height = c.string();
        if (!height.matches("[0-9]+(?:\\.[0-9]+)?")) throw bad();
        c.string();
        c.string();
        c.integer();
        c.string();
        c.integer();
        c.integer();
        c.integer();
        return new Added(id, type, new Target(x, y, rot));
    }

    public HPacket placement(int header, Handle handle, Target target) {
        return new ShockPacketOutgoing(
                header, handle.value(), target.x(), target.y(), target.rotation());
    }

    public HPacket redemption(int header, ObjectId id) {
        return new ShockPacketOutgoing(header, id.value());
    }

    public HPacket goldBarPurchase(int header) {
        // This legacy catalogue command uses raw CR-separated content, NOT appendString().
        return new ShockPacketOutgoing(
                header,
                "production\rorigins_habloons\ren\ra0 CF_50_goldbar\r-\r0\r\r\r"
                        .getBytes(StandardCharsets.ISO_8859_1));
    }

    public static IllegalArgumentException bad() {
        return new IllegalArgumentException("Unsupported or malformed Origins packet");
    }

    static final class Cursor {
        private final byte[] bytes;
        private int pos = 2;

        Cursor(byte[] bytes) {
            if (bytes.length < 2
                    || bytes.length > 1_000_000
                    || (bytes[0] & 255) < 64
                    || (bytes[0] & 255) > 127
                    || (bytes[1] & 255) < 64
                    || (bytes[1] & 255) > 127) throw bad();
            this.bytes = bytes;
        }

        int integer() {
            if (pos >= bytes.length) throw bad();
            int start = pos, f = bytes[pos] & 255, n = (f >> 3) & 7;
            if (f < 64 || f > 127 || n < 1 || n > 6 || pos + n > bytes.length) throw bad();
            long v = f & 3;
            for (int i = 1; i < n; i++) {
                int b = bytes[pos + i] & 255;
                if (b < 64 || b > 127) throw bad();
                v |= (long) (b & 63) << (2 + 6 * (i - 1));
            }
            if (v > Integer.MAX_VALUE) throw bad();
            int value = (int) ((f & 4) != 0 ? -v : v);
            pos += n;
            if (!Arrays.equals(Arrays.copyOfRange(bytes, start, pos), VL64Encoding.encode(value)))
                throw bad();
            return value;
        }

        int count(int max) {
            int n = integer();
            if (n < 0 || n > max) throw bad();
            return n;
        }

        String string() {
            int end = pos;
            while (end < bytes.length && bytes[end] != 2) end++;
            if (end == bytes.length || end - pos > 4096) throw bad();
            String s = new String(bytes, pos, end - pos, StandardCharsets.ISO_8859_1);
            pos = end + 1;
            return s;
        }

        String rest() {
            String s = new String(bytes, pos, bytes.length - pos, StandardCharsets.ISO_8859_1);
            pos = bytes.length;
            return s;
        }

        int decimal(String s) {
            if (!s.matches("[1-9][0-9]{0,9}")) throw bad();
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw bad();
            }
        }

        void end() {
            if (pos != bytes.length) throw bad();
        }
    }
}
