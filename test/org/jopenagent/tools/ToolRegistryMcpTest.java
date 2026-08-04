package org.jopenagent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.Test;

import org.jopenagent.core.AgentConfig;
import org.jopenagent.llm.ToolCall;
import org.jopenagent.mcp.McpClient;
import org.jopenagent.mcp.McpServerConfig;
import org.jopenagent.testsupport.McpAgent;
import org.jopenagent.testsupport.MockLlmClient;

/** Verifies MCP-sourced tools (see org.jopenagent.mcp.FakeMcpServer) are merged into ToolRegistry and correctly dispatched. */
class ToolRegistryMcpTest {

    private String javaExecutable() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }

    @Test
    void mcpToolsAreMergedIntoToolSpecsAndInvokable() {
        McpAgent agent = new McpAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
        String classpath = System.getProperty("java.class.path");
        agent.tools = new McpClient("fake", McpServerConfig.builder(javaExecutable()).arg("-cp").arg(classpath).arg("org.jopenagent.mcp.FakeMcpServer").build());
        try {
            ToolRegistry registry = new ToolRegistry(agent);

            assertTrue(registry.hasTool("tools__echo"));

            JSONObject args = new JSONObject();
            args.put("message", "hi");
            Object result = registry.invoke(new ToolCall("call-1", "tools__echo", args));

            assertEquals("echo: hi", result);
        } finally {
            agent.tools.close();
        }
    }
}
