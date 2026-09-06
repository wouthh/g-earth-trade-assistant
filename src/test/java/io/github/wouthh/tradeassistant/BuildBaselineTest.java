package io.github.wouthh.tradeassistant;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildBaselineTest {
    @Test void java21OrNewerIsAvailable() {
        assertTrue(Runtime.version().feature() >= 21);
    }
}
