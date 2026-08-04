package org.jopenagent.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launch configuration for a stdio-transport MCP server subprocess (the transport NOOA's own {@code MCPStdioClient} uses most commonly). */
public final class McpServerConfig {

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;

    private McpServerConfig(Builder builder) {
        this.command = builder.command;
        this.args = builder.args;
        this.env = builder.env;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArgs() {
        return args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public static Builder builder(String command) {
        return new Builder(command);
    }

    /** Fluent builder for {@link McpServerConfig}; {@code command} is required up front, args/env accumulate via repeated calls. */
    public static final class Builder {
        private final String command;
        private final List<String> args = new ArrayList<>();
        private final Map<String, String> env = new LinkedHashMap<>();

        private Builder(String command) {
            this.command = command;
        }

        public Builder arg(String arg) {
            args.add(arg);
            return this;
        }

        public Builder args(List<String> more) {
            args.addAll(more);
            return this;
        }

        public Builder env(String key, String value) {
            env.put(key, value);
            return this;
        }

        public McpServerConfig build() {
            return new McpServerConfig(this);
        }
    }
}
