package com.debugbridge.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.script.DirectDispatcher;
import com.debugbridge.core.server.BridgeServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AuthenticationTest {
    private static final int PORT = 19879;
    private static BridgeServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new BridgeServer(PORT, new PassthroughResolver("test"), new DirectDispatcher());
        server.setAuthToken("0123456789abcdef0123456789abcdef");
        server.start();
        Thread.sleep(300);
    }

    @AfterAll
    static void stop() throws Exception {
        server.stop();
    }

    @Test
    void requiresValidTokenBeforeAnyCapability() throws Exception {
        Client client = new Client(new URI("ws://127.0.0.1:" + PORT));
        assertTrue(client.connectBlocking(3, TimeUnit.SECONDS));
        try {
            JsonObject unauthenticated = request(client, "status", new JsonObject());
            assertFalse(unauthenticated.get("success").getAsBoolean());
            assertTrue(unauthenticated.get("error").getAsString().contains("AUTH_REQUIRED"));

            JsonObject wrongPayload = new JsonObject();
            wrongPayload.addProperty("token", "wrong");
            JsonObject wrong = request(client, "authenticate", wrongPayload);
            assertFalse(wrong.get("success").getAsBoolean());

            JsonObject authPayload = new JsonObject();
            authPayload.addProperty("token", "0123456789abcdef0123456789abcdef");
            JsonObject auth = request(client, "authenticate", authPayload);
            assertTrue(auth.get("success").getAsBoolean());

            JsonObject capabilities = request(client, "capabilities", new JsonObject());
            assertTrue(capabilities.get("success").getAsBoolean());
            assertTrue(capabilities.getAsJsonObject("result").get("execute").getAsBoolean());
        } finally {
            client.closeBlocking();
        }
    }

    private static JsonObject request(Client client, String type, JsonObject payload) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("id", "auth-" + System.nanoTime());
        request.addProperty("type", type);
        request.add("payload", payload);
        client.send(new Gson().toJson(request));
        String response = client.responses.poll(3, TimeUnit.SECONDS);
        assertNotNull(response);
        return JsonParser.parseString(response).getAsJsonObject();
    }

    private static final class Client extends WebSocketClient {
        private final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();

        Client(URI uri) {
            super(uri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {}

        @Override
        public void onMessage(String message) {
            responses.offer(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {}

        @Override
        public void onError(Exception ex) {}
    }
}
