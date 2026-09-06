package io.github.wouthh.tradeassistant;

import static io.github.wouthh.tradeassistant.domain.Model.*;
import static io.github.wouthh.tradeassistant.protocol.OriginsCodec.Binding.*;
import static org.junit.jupiter.api.Assertions.*;

import gearth.encoding.VL64Encoding;
import gearth.protocol.*;
import gearth.services.packet_info.*;
import io.github.wouthh.tradeassistant.domain.InventoryBook;
import io.github.wouthh.tradeassistant.protocol.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OriginsCodecTest {
    @Test
    void inventoryCapacityRejectsTheWholePageWithoutChangingPriorProvenance() {
        InventoryBook book = new InventoryBook();
        book.page(Harness.inventory(80));
        var before = book.available();
        List<Integer> handles =
                java.util.stream.IntStream.rangeClosed(1, 100_001).map(i -> -i).boxed().toList();
        Inventory oversized =
                new Inventory(
                        List.of(new Group(-1, handles, 1, "S", 1, 0, 0, BRONZE, 1, 1, "")), 0);
        assertThrows(IllegalStateException.class, () -> book.page(oversized));
        assertEquals(before, book.available());
    }

    final OriginsCodec codec = new OriginsCodec();

    static String resource(String name) throws Exception {
        try (var in = OriginsCodecTest.class.getResourceAsStream('/' + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8)
                    .strip();
        }
    }

    @ParameterizedTest
    @ValueSource(
            ints = {
                0,
                1,
                -1,
                127,
                -127,
                120045,
                -120045,
                10000000,
                -10000000,
                Integer.MAX_VALUE,
                -Integer.MAX_VALUE
            })
    void signedVl64AndDirectionalRoundTrip(int n) {
        byte[] value = VL64Encoding.encode(n);
        assertEquals(n, VL64Encoding.decode(value));
        HPacket packet =
                new gearth.protocol.packethandler.shockwave.packets.ShockPacketOutgoing(1245, n);
        HPacket decoded = codec.representation(packet.toString(), false);
        assertArrayEquals(packet.toBytes(), decoded.toBytes());
        assertEquals(HPacketFormat.WEDGIE_OUTGOING, decoded.getFormat());
        assertEquals(1245, decoded.headerId());
    }

    @Test
    void rawStringsAndExactGoldBarPacket() {
        HPacket purchase = codec.goldBarPurchase(100);
        String expected =
                "Adproduction[13]origins_habloons[13]en[13]a0 CF_50_goldbar[13]-[13]0[13][13][13]";
        assertArrayEquals(codec.representation(expected, false).toBytes(), purchase.toBytes());
        assertEquals(8, purchase.toString().split("\\[13\\]", -1).length - 1);
        assertArrayEquals(
                new byte[] {65, 65, 110, 101, 119}, codec.representation("AAnew", false).toBytes());
        assertEquals(
                new InventoryRequest("new"),
                codec.decode(GETSTRIP, codec.representation("AAnew", false).toBytes()));
    }

    @Test
    void strictMalformedTruncatedAndNonCanonicalBodies() {
        HPacket p = codec.placement(90, new Handle(-120045), Harness.TARGET);
        byte[] b = p.toBytes();
        for (int n = 0; n < b.length; n++) {
            byte[] truncated = Arrays.copyOf(b, n);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(PLACESTUFF, truncated));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(PLACESTUFF, Arrays.copyOf(b, b.length + 1)));
        for (String body :
                List.of("S[93]H", "S[93]L", "S[93]P@", "S[93][0]", "S[93]p@@@@@", "A^12oops")) {
            var binding = body.startsWith("A^") ? ACTIVEOBJECT_REMOVE : CONVERT_FURNI_TO_HABLOONS;
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            codec.decode(
                                    binding,
                                    codec.representation(body, binding.incoming).toBytes()),
                    body);
        }
    }

    @Test
    void groupedInventoryHasRealInstancesAndNeverClaimsCompleteness() throws Exception {
        HPacket p = codec.representation(resource("inventory.txt"), true);
        Inventory page = (Inventory) codec.decode(STRIPINFO_2, p.toBytes());
        assertEquals(3, page.groups().size());
        assertEquals(19, page.groups().getLast().handles().size());
        assertEquals(500, page.trailer());
        InventoryBook book = new InventoryBook();
        book.page(page);
        assertEquals(19, book.available().size());
        book.page(page);
        assertEquals(19, book.available().size());
        Handle removed = new Handle(page.groups().getLast().handles().get(4));
        book.removed(removed);
        book.page(page);
        assertFalse(book.contains(removed));
        Group old = page.groups().getLast();
        List<Integer> remaining = old.handles().stream().filter(h -> h != removed.value()).toList();
        Group refreshed =
                new Group(
                        remaining.get(1),
                        remaining,
                        old.slot(),
                        "S",
                        -remaining.get(1),
                        0,
                        0,
                        BRONZE,
                        1,
                        1,
                        "0,0,0");
        book.page(new Inventory(List.of(refreshed), 499));
        assertEquals(18, book.available().size());
        byte[] b = p.toBytes();
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(STRIPINFO_2, Arrays.copyOf(b, b.length - 1)));
    }

    @Test
    void fullInterleavedTracePreservesIdentityWithoutFifoAssumptions() throws Exception {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 17, false));
        Set<Integer> observed = new HashSet<>();
        for (String line : resource("manual-interleaved.txt").split("\\R")) {
            int split = line.indexOf(" -> ");
            boolean incoming = line.startsWith("Incoming");
            HPacket p = codec.representation(line.substring(split + 4), incoming);
            var binding =
                    Arrays.stream(OriginsCodec.Binding.values())
                            .filter(b -> b.incoming == incoming && b.header == p.headerId())
                            .findFirst()
                            .orElseThrow();
            Event e = codec.decode(binding, p.toBytes());
            if (e instanceof Placement pl) observed.add(pl.handle().expectedObject().value());
            h.engine.on(e);
        }
        assertEquals(17, h.engine.snapshot().placed());
        assertEquals(17, observed.size());
        Set<Integer> redeemed = new HashSet<>();
        for (int i = 0; i < 17; i++) {
            h.advance(i == 0 ? 0 : 1500);
            assertEquals(i + 1, h.sent.size());
            var sent = h.sent.get(i);
            redeemed.add(sent.object().value());
            h.confirm(sent);
        }
        assertEquals(observed, redeemed);
        assertEquals(State.COMPLETE, h.engine.snapshot().state());
    }

    @Test
    void mappingsAcceptOriginsMetadataAndRejectModernOrAmbiguousMaps() {
        var empty = new PacketBindings(PacketInfoManager.EMPTY);
        assertEquals(90, empty.id(PLACESTUFF));
        var shifted =
                new PacketInfo(
                        HMessage.Direction.TOSERVER, 190, null, "PLACESTUFF", null, "fixture");
        assertEquals(
                190,
                new PacketBindings(new PacketInfoManager("fixture", List.of(shifted)))
                        .id(PLACESTUFF));
        var modern =
                new PacketInfo(
                        HMessage.Direction.TOSERVER,
                        90,
                        null,
                        "PlaceStuffFromStripDEPRECATED",
                        null,
                        "fixture");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PacketBindings(new PacketInfoManager("fixture", List.of(modern))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PacketBindings(
                                new PacketInfoManager("fixture", List.of(shifted, shifted))));
    }

    @Test
    void roomReadyIsStrictAndIdentityTypesCannotBeMixed() {
        assertEquals(
                new RoomReady(42),
                codec.decode(ROOM_READY, codec.representation("AEmodel_a 42", true).toBytes()));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(ROOM_READY, codec.representation("AEgarbage", true).toBytes()));
        assertThrows(IllegalArgumentException.class, () -> new Handle(42));
        assertThrows(IllegalArgumentException.class, () -> new ObjectId(-42));
        assertThrows(IllegalArgumentException.class, () -> new Target(-1, 2, 0));
    }
}
