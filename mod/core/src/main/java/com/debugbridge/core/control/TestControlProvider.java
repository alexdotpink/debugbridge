package com.debugbridge.core.control;

import com.google.gson.JsonObject;

/** Signed request/response transport to the test server's structured automation channel. */
public interface TestControlProvider {
    JsonObject request(JsonObject payload) throws Exception;
}
