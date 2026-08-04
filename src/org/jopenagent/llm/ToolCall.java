package org.jopenagent.llm;

import org.jopenkit.json.JSONObject;

/** One tool invocation requested by the model: an id (for correlating the result), a tool name, and JSON arguments. */
public final class ToolCall {

    private final String id;
    private final String name;
    private final JSONObject arguments;

    public ToolCall(String id, String name, JSONObject arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments != null ? arguments : new JSONObject();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public JSONObject getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return name + "(" + arguments + ")";
    }
}
