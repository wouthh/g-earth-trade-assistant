package io.github.wouthh.tradeassistant;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildBaselineTest {
    @Test
    void java21OrNewerIsAvailable() {
        assertTrue(Runtime.version().feature() >= 21);
    }
}
