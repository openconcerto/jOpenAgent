package org.jopenagent.mcp;

import org.jopenkit.json.JSONObject;

/** One tool advertised by an MCP server's {@code tools/list} response. */
public final class McpToolDefinition {

    private final String name;
    private final String description;
    private final JSONObject inputSchema;

    public McpToolDefinition(String name, String description, JSONObject inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public JSONObject getInputSchema() {
        return inputSchema;
    }
}
