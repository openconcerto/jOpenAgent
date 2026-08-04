package org.jopenagent.llm;

import org.jopenkit.json.JSONObject;

/** A JSON-schema-described tool the model may call: execute_python/return_result (CodeAct), or a tools-via-self schema. */
public final class ToolSpec {

    private final String name;
    private final String description;
    private final JSONObject parametersSchema;

    public ToolSpec(String name, String description, JSONObject parametersSchema) {
        this.name = name;
        this.description = description;
        this.parametersSchema = parametersSchema;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public JSONObject getParametersSchema() {
        return parametersSchema;
    }
}
