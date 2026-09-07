package io.github.wouthh.tradeassistant.protocol;

import static io.github.wouthh.tradeassistant.protocol.OriginsCodec.Binding;

import gearth.protocol.HMessage.Direction;
import gearth.services.packet_info.PacketInfoManager;
import java.util.*;

public final class PacketBindings {
    private final EnumMap<Binding, Integer> ids = new EnumMap<>(Binding.class);

    public PacketBindings(PacketInfoManager metadata) {
        for (Binding b : Binding.values()) {
            Direction d = b.incoming ? Direction.TOCLIENT : Direction.TOSERVER;
            var named = metadata.getAllPacketInfoFromName(d, b.name());
            if (named.size() > 1) throw OriginsCodec.bad();
            int id = named.isEmpty() ? b.header : named.getFirst().getHeaderId();
            if (id < 0 || id > 4095) throw OriginsCodec.bad();
            var occupants = metadata.getAllPacketInfoFromHeaderId(d, id);
            if (occupants.stream()
                    .anyMatch(p -> p.getName() != null && !p.getName().equals(b.name())))
                throw OriginsCodec.bad();
            for (var e : ids.entrySet())
                if (e.getKey().incoming == b.incoming && e.getValue() == id)
                    throw OriginsCodec.bad();
            ids.put(b, id);
        }
    }

    public int id(Binding b) {
        return ids.get(b);
    }

    public Binding lookup(boolean incoming, int header) {
        return ids.entrySet().stream()
                .filter(e -> e.getKey().incoming == incoming && e.getValue() == header)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
