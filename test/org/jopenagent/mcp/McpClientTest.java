package org.jopenagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Exercises the real stdio JSON-RPC wire protocol against a real subprocess (not mocked), see
 * {@link FakeMcpServer}.
 */
class McpClientTest {

    private McpClient launchFakeServer() {
        String classpath = System.getProperty("java.class.path");
        McpServerConfig config = McpServerConfig.builder(javaExecutable()).arg("-cp").arg(classpath).arg("org.jopenagent.mcp.FakeMcpServer").build();
        return new McpClient("fake", config);
    }

    private String javaExecutable() {
        return System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
    }

    @Test
    void initializeListToolsAndCallToolRoundTrip() {
        try (McpClient client = launchFakeServer()) {
            List<McpToolDefinition> tools = client.listTools();
            assertEquals(1, tools.size());
            assertEquals("echo", tools.get(0).getName());
            assertEquals("object", tools.get(0).getInputSchema().getString("type"));

            JSONObject args = new JSONObject();
            args.put("message", "hello mcp");
            String result = client.callTool("echo", args);

            assertEquals("echo: hello mcp", result);
        }
    }

    @Test
    void listToolsIsCachedAfterFirstCall() {
        try (McpClient client = launchFakeServer()) {
            List<McpToolDefinition> first = client.listTools();
            List<McpToolDefinition> second = client.listTools();
            assertEquals(first, second);
        }
    }

    @Test
    void unreachableCommandThrowsMcpException() {
        assertThrows(McpException.class, new Executable() {
            @Override
            public void execute() {
                try (McpClient mcpClient = new McpClient("bad", McpServerConfig.builder("this-command-does-not-exist-anywhere").build())) {
                    // Nothing
                }

            }
        });
    }
}
