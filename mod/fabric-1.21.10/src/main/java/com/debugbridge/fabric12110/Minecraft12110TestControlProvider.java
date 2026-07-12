package com.debugbridge.fabric12110;

import com.debugbridge.core.control.TestControlProvider;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Fabric custom-payload transport for mgamemaker:test_control. */
public final class Minecraft12110TestControlProvider implements TestControlProvider {
    private static final Gson GSON = new Gson();
    private static final String HMAC = "HmacSHA256";
    private static final String RESPONSE_PREFIX = "[mgamemaker:test_control:v1]";
    private static final int MAX_BYTES = 30_000;
    private static final int DIRECT_TRANSPORT_WAIT_MS = 1_200;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, CompletableFuture<JsonObject>> PENDING = new ConcurrentHashMap<>();
    private static volatile boolean registered;
    private static volatile long lastReceivedAt;
    private static volatile String lastReceivedRequestId = "none";
    private static volatile String lastReceiverError = "none";

    private final byte[] secret;

    public Minecraft12110TestControlProvider(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("test_control_secret must contain at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        registerNetworking();
    }

    @Override
    public JsonObject request(JsonObject payload) throws Exception {
        String operation = payload.has("operation") ? payload.get("operation").getAsString() : "";
        if (operation.isBlank()) throw new IllegalArgumentException("operation is required");
        JsonObject args = payload.has("args") && payload.get("args").isJsonObject()
                ? payload.getAsJsonObject("args")
                : new JsonObject();
        int timeoutMs = payload.has("timeoutMs")
                ? Math.max(1_000, Math.min(payload.get("timeoutMs").getAsInt(), 30_000))
                : 10_000;

        long startedAt = System.nanoTime();
        JsonObject directRequest = buildRequest(operation, args);
        CompletableFuture<JsonObject> directResponse = registerPending(directRequest);
        try {
            ClientPlayNetworking.send(new TestControlPayload(encodeRequest(directRequest)));
            try {
                JsonObject value =
                        directResponse.get(Math.min(timeoutMs, DIRECT_TRANSPORT_WAIT_MS), TimeUnit.MILLISECONDS);
                verifyResponse(value);
                return value;
            } catch (TimeoutException ignored) {
                // Velocity can advertise the payload channel while dropping C2S payloads.
            }
        } finally {
            PENDING.remove(directRequest.get("requestId").getAsString());
        }

        int elapsedMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        int remainingMs = timeoutMs - elapsedMs;
        if (remainingMs < 1) throw timeout(timeoutMs, "custom-payload");

        // Use a distinct signed request so a delayed direct request cannot trip replay protection.
        JsonObject fallbackRequest = buildRequest(operation, args);
        byte[] fallbackBytes = encodeRequest(fallbackRequest);
        String command =
                "mgtestcontrol " + Base64.getUrlEncoder().withoutPadding().encodeToString(fallbackBytes);
        if (command.length() > MAX_BYTES)
            throw new IllegalArgumentException("test-control fallback command is too large");
        CompletableFuture<JsonObject> fallbackResponse = registerPending(fallbackRequest);
        try {
            dispatchCommand(command).get(Math.min(remainingMs, 2_000), TimeUnit.MILLISECONDS);
            JsonObject value;
            try {
                value = fallbackResponse.get(remainingMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                throw timeout(timeoutMs, "command-fallback");
            }
            verifyResponse(value);
            return value;
        } finally {
            PENDING.remove(fallbackRequest.get("requestId").getAsString());
        }
    }

    private JsonObject buildRequest(String operation, JsonObject args) throws Exception {
        byte[] nonceBytes = new byte[24];
        RANDOM.nextBytes(nonceBytes);
        JsonObject request = new JsonObject();
        request.addProperty("version", "1");
        request.addProperty("requestId", UUID.randomUUID().toString());
        request.addProperty("timestamp", System.currentTimeMillis());
        request.addProperty("nonce", HexFormat.of().formatHex(nonceBytes));
        request.addProperty("operation", operation);
        request.add("args", args.deepCopy());
        request.addProperty("signature", signRequest(request));
        return request;
    }

    private static byte[] encodeRequest(JsonObject request) {
        byte[] bytes = GSON.toJson(request).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("test-control request is too large");
        return bytes;
    }

    private static CompletableFuture<JsonObject> registerPending(JsonObject request) {
        CompletableFuture<JsonObject> response = new CompletableFuture<>();
        PENDING.put(request.get("requestId").getAsString(), response);
        return response;
    }

    private static CompletableFuture<Void> dispatchCommand(String command) {
        CompletableFuture<Void> dispatched = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                if (minecraft.getConnection() == null) throw new IllegalStateException("not connected to a server");
                minecraft.getConnection().sendCommand(command);
                dispatched.complete(null);
            } catch (Exception exception) {
                dispatched.completeExceptionally(exception);
            }
        });
        return dispatched;
    }

    private static IllegalStateException timeout(int timeoutMs, String transport) {
        boolean sendable = ClientPlayNetworking.canSend(TestControlPayload.TYPE);
        return new IllegalStateException("response timeout after " + timeoutMs + "ms via " + transport
                + "; sendable=" + sendable + ", pending=" + PENDING.size()
                + ", lastReceivedRequestId=" + lastReceivedRequestId + ", lastReceivedAt=" + lastReceivedAt
                + ", lastReceiverError=" + lastReceiverError);
    }

    private String signRequest(JsonObject request) throws Exception {
        String text = request.get("version").getAsString() + "\n"
                + request.get("requestId").getAsString() + "\n"
                + request.get("timestamp").getAsLong() + "\n"
                + request.get("nonce").getAsString() + "\n"
                + request.get("operation").getAsString() + "\n"
                + canonical(request.get("args"));
        return hmac(text);
    }

    private void verifyResponse(JsonObject response) throws Exception {
        String requestId = response.get("requestId").getAsString();
        long timestamp = response.get("timestamp").getAsLong();
        boolean success = response.get("success").getAsBoolean();
        JsonElement body = response.get(success ? "data" : "error");
        String expected = hmac(requestId + "\n" + timestamp + "\n" + success + "\n" + canonical(body));
        String supplied = response.get("signature").getAsString();
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("invalid FumazTest response signature");
        }
        if (Math.abs(System.currentTimeMillis() - timestamp) > 30_000L) {
            throw new SecurityException("stale FumazTest response");
        }
    }

    private String hmac(String value) throws Exception {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(secret, HMAC));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String canonical(JsonElement element) {
        if (element == null || element.isJsonNull()) return "null";
        if (element.isJsonPrimitive()) return GSON.toJson(element);
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) array.add(canonicalElement(child));
            return GSON.toJson(array);
        }
        JsonObject sorted = new JsonObject();
        TreeMap<String, JsonElement> fields = new TreeMap<>();
        element.getAsJsonObject()
                .entrySet()
                .forEach(entry -> fields.put(entry.getKey(), canonicalElement(entry.getValue())));
        fields.forEach(sorted::add);
        return GSON.toJson(sorted);
    }

    private static JsonElement canonicalElement(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) return element;
        return GSON.fromJson(canonical(element), JsonElement.class);
    }

    private static synchronized void registerNetworking() {
        if (registered) return;
        PayloadTypeRegistry.playC2S().register(TestControlPayload.TYPE, TestControlPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TestControlPayload.TYPE, TestControlPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(TestControlPayload.TYPE, (payload, context) -> {
            if (payload.data.length == 0 || payload.data.length > MAX_BYTES) return;
            acceptResponse(payload.data);
        });
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String text = message.getString();
            if (!text.startsWith(RESPONSE_PREFIX)) return true;
            try {
                acceptResponse(Base64.getUrlDecoder().decode(text.substring(RESPONSE_PREFIX.length())));
            } catch (Exception exception) {
                lastReceiverError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            }
            return false;
        });
        registered = true;
    }

    private static void acceptResponse(byte[] bytes) {
        try {
            if (bytes.length == 0 || bytes.length > MAX_BYTES)
                throw new IllegalArgumentException("invalid response size");
            JsonObject response = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            String requestId = response.get("requestId").getAsString();
            lastReceivedAt = System.currentTimeMillis();
            lastReceivedRequestId = requestId;
            lastReceiverError = "none";
            CompletableFuture<JsonObject> pending = PENDING.get(requestId);
            if (pending != null) pending.complete(response);
        } catch (Exception exception) {
            lastReceiverError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    public record TestControlPayload(byte[] data) implements CustomPacketPayload {
        public static final Type<TestControlPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("mgamemaker", "test_control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TestControlPayload> CODEC =
                StreamCodec.of((buffer, payload) -> buffer.writeBytes(payload.data), buffer -> {
                    int length = buffer.readableBytes();
                    if (length < 0 || length > MAX_BYTES)
                        throw new IllegalArgumentException("invalid test-control payload length");
                    byte[] bytes = new byte[length];
                    buffer.readBytes(bytes);
                    return new TestControlPayload(bytes);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
