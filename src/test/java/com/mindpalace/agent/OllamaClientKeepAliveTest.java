package com.mindpalace.agent;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Zero-network guard for the keep_alive field OllamaClient sends (PR #424).
 * 2026-11-22: multi-resident mode sent keep_alive=-1, which left
 * deepseek-r1:1.5b resident "Forever" on an idle game. These tests fail if a
 * refactor brings pin-forever back as the default.
 */
class OllamaClientKeepAliveTest {

    private static final String PROP = "mindpalace.ollama.pinKeepAlive";

    @AfterEach
    void clearProp() {
        System.clearProperty(PROP);
    }

    private static String body(String keepAlive) {
        JsonObject b = new JsonObject();
        b.addProperty("model", "deepseek-r1:1.5b");
        OllamaClient.putKeepAlive(b, keepAlive);
        return b.toString();
    }

    @Test
    void defaultPinIsBoundedNotForever() {
        assumeTrue(System.getenv("MP_OLLAMA_PIN_KEEP_ALIVE") == null, "env override set");
        String pin = OllamaClient.resolvePinKeepAlive();
        assertEquals("15m", pin);
        String effective = OllamaClient.keepAliveValue(true, pin, "5m");
        assertNotEquals("-1", effective);
        assertEquals("{\"model\":\"deepseek-r1:1.5b\",\"keep_alive\":\"15m\"}", body(effective));
    }

    @Test
    void customPinFromSystemProperty() {
        System.setProperty(PROP, " 30m ");
        String pin = OllamaClient.resolvePinKeepAlive();
        assertEquals("30m", pin);
        assertEquals("{\"model\":\"deepseek-r1:1.5b\",\"keep_alive\":\"30m\"}", body(pin));
    }

    @Test
    void legacyMinusOneGoesOutAsJsonNumber() {
        System.setProperty(PROP, "-1");
        String pin = OllamaClient.resolvePinKeepAlive();
        // Must be the number -1, never the string "-1" (Ollama 0.40 answers 400).
        assertEquals("{\"model\":\"deepseek-r1:1.5b\",\"keep_alive\":-1}", body(pin));
    }

    @Test
    void blankPropertyFallsBackToDefault() {
        assumeTrue(System.getenv("MP_OLLAMA_PIN_KEEP_ALIVE") == null, "env override set");
        System.setProperty(PROP, "  ");
        assertEquals("15m", OllamaClient.resolvePinKeepAlive());
    }

    @Test
    void oneHotModeKeepsItsOwnWindow() {
        assertEquals("5m", OllamaClient.keepAliveValue(false, "15m", "5m"));
        assertEquals("{\"model\":\"deepseek-r1:1.5b\",\"keep_alive\":0}", body("0"));
    }
}
