package io.github.wouthh.tradeassistant;

import static io.github.wouthh.tradeassistant.domain.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.wouthh.tradeassistant.domain.ConversionEngine;
import io.github.wouthh.tradeassistant.protocol.TradeAssistantExtension;
import io.github.wouthh.tradeassistant.runtime.SafetyGate;
import io.github.wouthh.tradeassistant.runtime.SerialRuntime;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostLifecycleTest {
    @TempDir Path temp;

    private <T> T field(TradeAssistantExtension extension, String name, Class<T> type)
            throws Exception {
        var field = TradeAssistantExtension.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(extension));
    }

    @Test
    void repeatedHostInitializationInvalidatesAnArmedSession() throws Exception {
        String old = System.getProperty("tradeassistant.stateDir");
        System.setProperty("tradeassistant.stateDir", temp.toString());
        try (TradeAssistantExtension extension = new TradeAssistantExtension(new String[0])) {
            // Construct only: never run the host socket or inject a packet.
            SerialRuntime runtime = field(extension, "runtime", SerialRuntime.class);
            ConversionEngine engine = field(extension, "engine", ConversionEngine.class);
            SafetyGate gate = field(extension, "gate", SafetyGate.class);
            CountDownLatch armed = new CountDownLatch(1);
            runtime.execute(
                    () -> {
                        engine.connected(true);
                        engine.on(new RoomReady(42));
                        engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
                        armed.countDown();
                    });
            assertTrue(armed.await(5, TimeUnit.SECONDS));
            assertEquals(State.ARMED, engine.snapshot().state());
            long before = gate.generation();
            extension.initExtension();
            assertNotEquals(before, gate.generation());
            CountDownLatch initialized = new CountDownLatch(1);
            runtime.execute(initialized::countDown);
            assertTrue(initialized.await(5, TimeUnit.SECONDS));
            assertEquals(State.DISARMED, engine.snapshot().state());
            assertEquals(0, engine.snapshot().roomId());
        } finally {
            if (old == null) System.clearProperty("tradeassistant.stateDir");
            else System.setProperty("tradeassistant.stateDir", old);
        }
    }
}
