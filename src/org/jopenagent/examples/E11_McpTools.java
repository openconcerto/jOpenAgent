package org.jopenagent.examples;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.core.strategy.StrategyKind;
import org.jopenagent.mcp.McpClient;
import org.jopenagent.mcp.McpException;
import org.jopenagent.mcp.McpServerConfig;

/**
 * Mirrors nooa/examples/quickstart/11_mcp.py: an MCP server is attached to the agent as a plain
 * field (here using the official MCP reference "everything" server via {@code npx}, which exposes
 * a simple {@code echo} tool: no NVIDIA/Anthropic-specific server needed). Its tools are reachable
 * two ways, both shown here: directly from {@code CODE_ACT}-generated code
 * ({@code self.everything.callTool(...)}), and via {@link org.jopenagent.tools.ToolRegistry}'s
 * auto-merge for {@code TOOL_CALLING} (as {@code everything__echo}).
 *
 * <p>Requires Node's {@code npx} on PATH (prints a clear message and exits if it isn't).
 */
@SystemPrompt("You are an agent with access to an external MCP tool server.")
public class E11_McpTools extends Agent {

    /**
     * The attached MCP server connection, assigned in {@link #main} once the child process
     * launches successfully (not in the constructor, since launching {@code npx} can fail). Its
     * tools are reachable both directly ({@link #echoViaCode}) and via
     * {@link org.jopenagent.tools.ToolRegistry}'s auto-merge ({@link #echoViaTool}).
     */
    public McpClient everything;

    public E11_McpTools(AgentConfig config) {
        super(config);
    }

    /**
     * Reaches the MCP server's {@code echo} tool via {@code TOOL_CALLING}:
     * {@link org.jopenagent.tools.ToolRegistry} merges {@link #everything}'s advertised tools into the
     * schema list as {@code everything__echo}.
     */
    @Generative(value = "Use the echo tool on the everything server to repeat back the given phrase, then confirm you did so.", strategy = StrategyKind.TOOL_CALLING)
    public String echoViaTool(String phrase) {
        return generate(phrase);
    }

    /**
     * Reaches the same MCP {@code echo} tool via {@code CODE_ACT} instead: the generated code
     * calls {@code self.everything.callTool(...)} directly, with no tool-schema merge involved.
     */
    @Generative(value = "Write code that calls self.everything.callTool(\"echo\", a JSONObject with a \"message\" field) to echo the given phrase.",
            strategy = StrategyKind.CODE_ACT)
    public String echoViaCode(String phrase) {
        return generate(phrase);
    }

    public static void main(String[] args) {
        E11_McpTools agent = new E11_McpTools(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        try {
            agent.everything = new McpClient("everything", McpServerConfig.builder("npx").arg("-y").arg("@modelcontextprotocol/server-everything").build());
        } catch (McpException e) {
            System.out.println("Could not launch the MCP reference server (is Node's `npx` installed and on PATH?): " + e.getMessage());
            return;
        }
        try {
            System.out.println("Tools advertised by the 'everything' MCP server:");
            for (var tool : agent.everything.listTools()) {
                System.out.println("  " + tool.getName() + ": " + tool.getDescription());
            }
            System.out.println(agent.echoViaTool("hello from jOpenAgent"));
        } finally {
            agent.everything.close();
        }
    }
}
