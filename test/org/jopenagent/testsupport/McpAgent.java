package org.jopenagent.testsupport;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.mcp.McpClient;

/** Fixture agent with an MCP server attached as a plain field, for ToolRegistry MCP-merging tests. */
@SystemPrompt("You are an MCP-enabled agent.")
public class McpAgent extends Agent {

    public McpClient tools;

    public McpAgent(AgentConfig config) {
        super(config);
    }
}
