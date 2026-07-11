package com.debugbridge.core.control;

import com.google.gson.JsonObject;

/**
 * Version adapter for deterministic real-client input, UI and HUD control.
 * Implementations must execute Minecraft API work on the game thread.
 */
public interface ClientControlProvider {
    JsonObject applyInput(JsonObject payload) throws Exception;

    JsonObject applyLook(JsonObject payload) throws Exception;

    JsonObject inspectScreen() throws Exception;

    JsonObject screenAction(JsonObject payload) throws Exception;

    JsonObject inspectHud() throws Exception;

    JsonObject readEvents(JsonObject payload) throws Exception;

    /** Called once per Minecraft client tick on the game thread. */
    default void onClientTick() {}

    /** Release held input and temporary listeners when the controlling connection ends. */
    void cleanup();
}
