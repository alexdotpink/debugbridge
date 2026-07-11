package com.debugbridge.fabric12110;

import com.debugbridge.core.control.ClientControlProvider;
import com.debugbridge.fabric12110.mixin.BossHealthOverlayAccessor;
import com.debugbridge.fabric12110.mixin.GuiAccessor;
import com.debugbridge.fabric12110.mixin.PlayerTabOverlayAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

/** Real-input and UI adapter for exact Minecraft 1.21.10. */
public final class Minecraft12110ClientControlProvider implements ClientControlProvider {
    private static final int MAX_EVENTS = 2_000;
    private static final int MAX_EVENT_READ = 500;

    private final Map<KeyMapping, Long> releaseAtTick = new LinkedHashMap<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long tick;
    private long eventCursor;
    private long screenRevision;
    private Screen previousScreen;
    private boolean previousInWorld;
    private boolean previousDead;
    private int previousHotbar = -1;

    @Override
    public JsonObject applyInput(JsonObject payload) throws Exception {
        return onGameThread(() -> {
            String operation = string(payload, "operation", "set");
            if ("releaseAll".equals(operation)) {
                releaseAll();
                JsonObject result = new JsonObject();
                result.addProperty("released", true);
                return result;
            }

            List<String> names = inputNames(payload);
            if (names.isEmpty()) throw new IllegalArgumentException("input requires 'key' or non-empty 'keys'");
            boolean down = bool(payload, "down", true);
            int durationTicks = Math.max(1, integer(payload, "durationTicks", 1));
            JsonArray states = new JsonArray();
            for (String name : names) {
                KeyMapping mapping = keyMapping(name);
                if ("tap".equals(operation)) {
                    KeyMapping.click(mapping.getDefaultKey());
                    mapping.setDown(true);
                    releaseAtTick.put(mapping, tick + durationTicks);
                } else {
                    mapping.setDown(down);
                    if (down && payload.has("durationTicks")) {
                        releaseAtTick.put(mapping, tick + durationTicks);
                    } else if (!down) {
                        releaseAtTick.remove(mapping);
                    }
                }
                JsonObject state = new JsonObject();
                state.addProperty("key", name);
                state.addProperty("down", mapping.isDown());
                states.add(state);
            }
            JsonObject result = new JsonObject();
            result.add("keys", states);
            result.addProperty("tick", tick);
            appendEvent("input", result.deepCopy());
            return result;
        });
    }

    @Override
    public JsonObject applyLook(JsonObject payload) throws Exception {
        return onGameThread(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) throw new IllegalStateException("player is not in a world");
            String operation = string(payload, "operation", "set");
            float yaw = number(payload, "yaw", 0f);
            float pitch = number(payload, "pitch", 0f);
            if ("delta".equals(operation)) {
                yaw += mc.player.getYRot();
                pitch += mc.player.getXRot();
            }
            pitch = Math.max(-90f, Math.min(90f, pitch));
            mc.player.setYRot(yaw);
            mc.player.setYHeadRot(yaw);
            mc.player.setXRot(pitch);
            JsonObject result = new JsonObject();
            result.addProperty("yaw", yaw);
            result.addProperty("pitch", pitch);
            appendEvent("look", result.deepCopy());
            return result;
        });
    }

    @Override
    public JsonObject inspectScreen() throws Exception {
        return onGameThread(this::inspectScreenOnGameThread);
    }

    private JsonObject inspectScreenOnGameThread() {
        Minecraft mc = Minecraft.getInstance();
        updateScreenRevision(mc.screen);
        JsonObject result = new JsonObject();
        result.addProperty("revision", screenRevision);
        Screen screen = mc.screen;
        if (screen == null) {
            result.addProperty("open", false);
            return result;
        }
        result.addProperty("open", true);
        result.addProperty("type", screen.getClass().getName());
        result.addProperty("title", screen.getTitle().getString());
        result.addProperty("width", screen.width);
        result.addProperty("height", screen.height);

        JsonArray widgets = new JsonArray();
        List<? extends GuiEventListener> children = screen.children();
        for (int i = 0; i < children.size(); i++) {
            GuiEventListener child = children.get(i);
            JsonObject widget = new JsonObject();
            widget.addProperty("ref", "w" + i);
            widget.addProperty("type", child.getClass().getName());
            widget.addProperty("focused", child.isFocused());
            if (child instanceof AbstractWidget aw) {
                widget.addProperty("message", aw.getMessage().getString());
                widget.addProperty("x", aw.getX());
                widget.addProperty("y", aw.getY());
                widget.addProperty("width", aw.getWidth());
                widget.addProperty("height", aw.getHeight());
                widget.addProperty("active", aw.active);
                widget.addProperty("visible", aw.visible);
            }
            widgets.add(widget);
        }
        result.add("widgets", widgets);

        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            AbstractContainerMenu menu = containerScreen.getMenu();
            result.addProperty("containerId", menu.containerId);
            result.addProperty("menuType", menu.getClass().getName());
            JsonArray slots = new JsonArray();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                JsonObject slotJson = new JsonObject();
                slotJson.addProperty("slot", i);
                slotJson.addProperty("containerSlot", slot.getContainerSlot());
                slotJson.addProperty("x", slot.x);
                slotJson.addProperty("y", slot.y);
                slotJson.addProperty("active", slot.isActive());
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) slotJson.add("item", item(stack));
                slots.add(slotJson);
            }
            result.add("slots", slots);
        }
        return result;
    }

    @Override
    public JsonObject screenAction(JsonObject payload) throws Exception {
        return onGameThread(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen screen = mc.screen;
            if (screen == null) throw new IllegalStateException("no screen is open");
            updateScreenRevision(screen);
            long requestedRevision =
                    payload.has("revision") ? payload.get("revision").getAsLong() : screenRevision;
            if (requestedRevision != screenRevision) {
                throw new IllegalStateException(
                        "STALE_SCREEN: expected revision " + screenRevision + " but received " + requestedRevision);
            }
            String action = string(payload, "action", "");
            boolean handled;
            switch (action) {
                case "clickWidget" -> {
                    int index = widgetIndex(string(payload, "ref", ""));
                    List<? extends GuiEventListener> children = screen.children();
                    if (index < 0 || index >= children.size()) throw new IllegalArgumentException("unknown widget ref");
                    GuiEventListener child = children.get(index);
                    if (!(child instanceof AbstractWidget aw))
                        throw new IllegalArgumentException("widget is not clickable");
                    int button = integer(payload, "button", 0);
                    handled = aw.mouseClicked(
                            mouse(aw.getX() + aw.getWidth() / 2.0, aw.getY() + aw.getHeight() / 2.0, button), false);
                }
                case "clickAt" ->
                    handled = screen.mouseClicked(
                            mouse(number(payload, "x", 0), number(payload, "y", 0), integer(payload, "button", 0)),
                            false);
                case "clickSlot" -> {
                    if (!(screen instanceof AbstractContainerScreen<?> cs)) {
                        throw new IllegalStateException("current screen is not a container");
                    }
                    if (mc.player == null || mc.gameMode == null)
                        throw new IllegalStateException("player is unavailable");
                    int slot = integer(payload, "slot", -1);
                    if (slot < 0 || slot >= cs.getMenu().slots.size())
                        throw new IllegalArgumentException("invalid slot");
                    int button = integer(payload, "button", 0);
                    ClickType clickType = ClickType.valueOf(
                            string(payload, "clickType", "PICKUP").toUpperCase());
                    mc.gameMode.handleInventoryMouseClick(cs.getMenu().containerId, slot, button, clickType, mc.player);
                    handled = true;
                }
                case "keyPress" ->
                    handled = screen.keyPressed(new KeyEvent(
                            integer(payload, "key", 256),
                            integer(payload, "scancode", 0),
                            integer(payload, "modifiers", 0)));
                case "close" -> {
                    screen.onClose();
                    handled = true;
                }
                default -> throw new IllegalArgumentException("unknown screen action: " + action);
            }
            JsonObject result = new JsonObject();
            result.addProperty("action", action);
            result.addProperty("handled", handled);
            result.addProperty("revision", screenRevision);
            appendEvent("screen_action", result.deepCopy());
            return result;
        });
    }

    @Override
    public JsonObject inspectHud() throws Exception {
        return onGameThread(() -> {
            Minecraft mc = Minecraft.getInstance();
            JsonObject result = new JsonObject();
            result.addProperty("inWorld", mc.player != null && mc.level != null);
            if (mc.player != null) {
                JsonObject player = new JsonObject();
                player.addProperty("name", mc.player.getName().getString());
                player.addProperty("health", mc.player.getHealth());
                player.addProperty("food", mc.player.getFoodData().getFoodLevel());
                player.addProperty("hotbarSlot", mc.player.getInventory().getSelectedSlot());
                player.addProperty("dead", mc.player.isDeadOrDying());
                result.add("player", player);
            }
            ServerData server = mc.getCurrentServer();
            if (server != null) {
                JsonObject serverJson = new JsonObject();
                serverJson.addProperty("name", server.name);
                serverJson.addProperty("address", server.ip);
                serverJson.addProperty(
                        "resourcePackStatus", server.getResourcePackStatus().name());
                result.add("server", serverJson);
            }

            GuiAccessor gui = (GuiAccessor) mc.gui;
            addComponent(result, "title", gui.debugbridge$getTitle());
            addComponent(result, "subtitle", gui.debugbridge$getSubtitle());
            addComponent(result, "actionbar", gui.debugbridge$getOverlayMessage());

            PlayerTabOverlayAccessor tab = (PlayerTabOverlayAccessor) mc.gui.getTabList();
            JsonObject tabJson = new JsonObject();
            addComponent(tabJson, "header", tab.debugbridge$getHeader());
            addComponent(tabJson, "footer", tab.debugbridge$getFooter());
            JsonArray players = new JsonArray();
            ClientPacketListener connection = mc.getConnection();
            if (connection != null) {
                for (PlayerInfo info : connection.getListedOnlinePlayers()) {
                    JsonObject p = new JsonObject();
                    p.addProperty("name", info.getProfile().name());
                    p.addProperty(
                            "displayName",
                            mc.gui.getTabList().getNameForDisplay(info).getString());
                    p.addProperty("latency", info.getLatency());
                    p.addProperty(
                            "gameMode",
                            info.getGameMode() == null
                                    ? "unknown"
                                    : info.getGameMode().getName());
                    players.add(p);
                }
            }
            tabJson.add("players", players);
            result.add("tabList", tabJson);

            JsonArray bosses = new JsonArray();
            for (LerpingBossEvent boss : ((BossHealthOverlayAccessor) mc.gui.getBossOverlay())
                    .debugbridge$getEvents()
                    .values()) {
                JsonObject b = new JsonObject();
                b.addProperty("id", boss.getId().toString());
                b.addProperty("name", boss.getName().getString());
                b.addProperty("progress", boss.getProgress());
                b.addProperty("color", boss.getColor().name());
                bosses.add(b);
            }
            result.add("bossBars", bosses);

            if (connection != null) {
                Scoreboard scoreboard = connection.scoreboard();
                Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
                if (sidebar != null) {
                    JsonObject sidebarJson = new JsonObject();
                    sidebarJson.addProperty("name", sidebar.getName());
                    sidebarJson.addProperty("title", sidebar.getDisplayName().getString());
                    JsonArray lines = new JsonArray();
                    for (PlayerScoreEntry score : scoreboard.listPlayerScores(sidebar)) {
                        if (score.isHidden()) continue;
                        JsonObject line = new JsonObject();
                        line.addProperty("owner", score.owner());
                        line.addProperty("text", score.ownerName().getString());
                        line.addProperty("score", score.value());
                        lines.add(line);
                    }
                    sidebarJson.add("lines", lines);
                    result.add("sidebar", sidebarJson);
                }
            }
            result.addProperty("screenRevision", screenRevision);
            return result;
        });
    }

    @Override
    public JsonObject readEvents(JsonObject payload) {
        long after = payload.has("after") ? payload.get("after").getAsLong() : 0;
        int limit = Math.min(MAX_EVENT_READ, Math.max(1, integer(payload, "limit", 100)));
        JsonArray out = new JsonArray();
        synchronized (events) {
            for (Event event : events) {
                if (event.cursor <= after) continue;
                JsonObject json = new JsonObject();
                json.addProperty("cursor", event.cursor);
                json.addProperty("tick", event.tick);
                json.addProperty("type", event.type);
                json.add("data", event.data);
                out.add(json);
                if (out.size() >= limit) break;
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("cursor", eventCursor);
        result.add("events", out);
        return result;
    }

    @Override
    public void onClientTick() {
        tick++;
        releaseAtTick.entrySet().removeIf(entry -> {
            if (entry.getValue() > tick) return false;
            entry.getKey().setDown(false);
            return true;
        });
        Minecraft mc = Minecraft.getInstance();
        updateScreenRevision(mc.screen);
        boolean inWorld = mc.player != null && mc.level != null;
        if (inWorld != previousInWorld) {
            JsonObject data = new JsonObject();
            data.addProperty("inWorld", inWorld);
            appendEvent(inWorld ? "world_join" : "world_leave", data);
            previousInWorld = inWorld;
        }
        if (mc.player != null) {
            boolean dead = mc.player.isDeadOrDying();
            if (dead != previousDead) {
                JsonObject data = new JsonObject();
                data.addProperty("dead", dead);
                appendEvent(dead ? "death" : "respawn", data);
                previousDead = dead;
            }
            int hotbar = mc.player.getInventory().getSelectedSlot();
            if (hotbar != previousHotbar) {
                JsonObject data = new JsonObject();
                data.addProperty("slot", hotbar);
                appendEvent("hotbar", data);
                previousHotbar = hotbar;
            }
        }
    }

    @Override
    public void cleanup() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(this::releaseAll);
    }

    private void updateScreenRevision(Screen screen) {
        if (screen == previousScreen) return;
        previousScreen = screen;
        screenRevision++;
        JsonObject data = new JsonObject();
        data.addProperty("revision", screenRevision);
        data.addProperty("open", screen != null);
        if (screen != null) {
            data.addProperty("type", screen.getClass().getName());
            data.addProperty("title", screen.getTitle().getString());
        }
        appendEvent(screen == null ? "screen_close" : "screen_open", data);
    }

    private void releaseAll() {
        KeyMapping.releaseAll();
        releaseAtTick.clear();
    }

    private KeyMapping keyMapping(String name) {
        Options o = Minecraft.getInstance().options;
        return switch (name) {
            case "forward" -> o.keyUp;
            case "back" -> o.keyDown;
            case "left" -> o.keyLeft;
            case "right" -> o.keyRight;
            case "jump" -> o.keyJump;
            case "sneak" -> o.keyShift;
            case "sprint" -> o.keySprint;
            case "inventory" -> o.keyInventory;
            case "swap_offhand" -> o.keySwapOffhand;
            case "drop" -> o.keyDrop;
            case "use" -> o.keyUse;
            case "attack" -> o.keyAttack;
            case "pick" -> o.keyPickItem;
            case "chat" -> o.keyChat;
            case "player_list" -> o.keyPlayerList;
            case "command" -> o.keyCommand;
            default -> {
                if (name.startsWith("hotbar_")) {
                    int slot = Integer.parseInt(name.substring("hotbar_".length())) - 1;
                    if (slot >= 0 && slot < o.keyHotbarSlots.length) yield o.keyHotbarSlots[slot];
                }
                KeyMapping dynamic = KeyMapping.get(name);
                if (dynamic == null) throw new IllegalArgumentException("unknown key mapping: " + name);
                yield dynamic;
            }
        };
    }

    private static JsonObject item(ItemStack stack) {
        JsonObject item = new JsonObject();
        item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("count", stack.getCount());
        item.addProperty("name", stack.getHoverName().getString());
        if (stack.isDamageableItem()) {
            item.addProperty("damage", stack.getDamageValue());
            item.addProperty("maxDamage", stack.getMaxDamage());
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            JsonArray lines = new JsonArray();
            for (Component line : lore.lines()) lines.add(line.getString());
            item.add("lore", lines);
        }
        return item;
    }

    private static MouseButtonEvent mouse(double x, double y, int button) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }

    private static int widgetIndex(String ref) {
        if (!ref.startsWith("w")) return -1;
        try {
            return Integer.parseInt(ref.substring(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static void addComponent(JsonObject target, String key, Component value) {
        if (value != null) target.addProperty(key, value.getString());
    }

    private void appendEvent(String type, JsonObject data) {
        synchronized (events) {
            events.addLast(new Event(++eventCursor, tick, type, data));
            while (events.size() > MAX_EVENTS) events.removeFirst();
        }
    }

    private static List<String> inputNames(JsonObject payload) {
        List<String> names = new ArrayList<>();
        if (payload.has("key")) names.add(payload.get("key").getAsString());
        if (payload.has("keys") && payload.get("keys").isJsonArray()) {
            payload.getAsJsonArray("keys").forEach(v -> names.add(v.getAsString()));
        }
        return names;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private static float number(JsonObject object, String key, float fallback) {
        return object.has(key) ? object.get(key).getAsFloat() : fallback;
    }

    private static <T> T onGameThread(Callable<T> task) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) return task.call();
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    private record Event(long cursor, long tick, String type, JsonObject data) {}
}
