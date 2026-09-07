package io.github.wouthh.tradeassistant;

import static org.junit.jupiter.api.Assertions.*;

import io.github.wouthh.tradeassistant.domain.Model.*;
import io.github.wouthh.tradeassistant.protocol.OriginsCodec;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Reads a caller-selected private document locally; no private capture is a public fixture. */
@EnabledIfSystemProperty(named = "privateEvidence", matches = ".+")
class PrivateEvidenceTest {
    @Test
    void suppliedInventoryAndAllPlacementIdentitiesAreVerified() throws Exception {
        String text = Files.readString(Path.of(System.getProperty("privateEvidence")));
        text = text.substring(text.indexOf("# Private packet-evidence appendix"));
        OriginsCodec codec = new OriginsCodec();
        Map<Integer, Inventory> pages = new LinkedHashMap<>();
        Set<Integer> placements = new HashSet<>(),
                stripRemovals = new HashSet<>(),
                roomAdds = new HashSet<>();
        int pageCount = 0;
        Matcher m = Pattern.compile("(Outgoing|Incoming)\\[(\\d+)] -> (.*)").matcher(text);
        while (m.find()) {
            boolean incoming = m.group(1).equals("Incoming");
            var p = codec.representation(m.group(3), incoming);
            int header = Integer.parseInt(m.group(2));
            assertEquals(header, p.headerId(), "Header encoding mismatch");
            var binding =
                    Arrays.stream(OriginsCodec.Binding.values())
                            .filter(b -> b.incoming == incoming && b.header == header)
                            .findFirst()
                            .orElseThrow();
            Event event = codec.decode(binding, p.toBytes());
            if (event instanceof Inventory inv) pages.put(pageCount++, inv);
            if (event instanceof Placement place)
                placements.add(place.handle().expectedObject().value());
            if (event instanceof StripRemoval removed)
                stripRemovals.add(removed.handle().expectedObject().value());
            if (event instanceof Added added) roomAdds.add(added.id().value());
        }
        assertEquals(3, pageCount, "Expected all supplied inventory frames");
        assertEquals(18, placements.size(), "Expected single-item example plus the 17-item trace");
        assertTrue(placements.equals(stripRemovals), "Placement/removal identity mismatch");
        assertTrue(placements.equals(roomAdds), "Placement/room-add identity mismatch");
        var before =
                pages.get(1).groups().stream()
                        .filter(g -> g.type().equals("CF_1_coin_bronze"))
                        .findFirst()
                        .orElseThrow();
        var after =
                pages.get(2).groups().stream()
                        .filter(g -> g.type().equals("CF_1_coin_bronze"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(before.handles().size() - 1, after.handles().size());
        Set<Integer> delta = new HashSet<>(before.handles());
        delta.removeAll(after.handles());
        assertEquals(1, delta.size());
        assertTrue(
                placements.contains(-delta.iterator().next()),
                "Refresh removal did not match a placed instance");
        assertArrayEquals(
                codec.goldBarPurchase(100).toBytes(),
                codec.representation(
                                text.substring(text.lastIndexOf("Adproduction")).split("\\R")[0],
                                false)
                        .toBytes());
        System.out.println(
                "Private evidence PASS: all three full inventory frames, 18 identities, signed handle/object correlation, and purchase bytes. No raw evidence emitted.");
    }
}
