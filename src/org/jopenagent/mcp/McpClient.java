package org.jopenagent.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

import org.jopenagent.util.ProcessLaunch;

/**
 * A minimal MCP (Model Context Protocol) client over the stdio transport: spawns the server as a
 * subprocess and speaks newline-delimited JSON-RPC 2.0 over its stdin/stdout, the {@code
 * initialize} / {@code tools/list} / {@code tools/call} exchange NOOA gets from wrapping the
 * official Python {@code mcp} SDK's {@code ClientSession}. jOpenAgent has no such SDK to wrap (and
 * adding one would violate the "no external HTTP/protocol library" constraint), so this
 * implements the wire protocol directly with {@code java.lang.ProcessBuilder} and the existing
 * {@code org.jopenkit.json} parser: sufficient for the request/response shapes real MCP servers
 * (e.g. the reference filesystem/fetch/git servers) use.
 *
 * <p>Unlike NOOA's dynamic-class-per-tool generation (which uses Python's {@code ast} module to
 * synthesize a real typed method per MCP tool: not portable to Java without bytecode generation,
 * a path already rejected in the core interception design), an {@code McpClient} instance is
 * attached to an agent as a plain field and its tools are reached either directly from
 * {@code CODE_ACT}-generated code (<code>self.wiki.callTool("search", args)</code>) or merged
 * into {@link org.jopenagent.tools.ToolRegistry}'s schema list for {@code TOOL_CALLING} (tool names
 * prefixed {@code fieldName__toolName} to avoid collisions).
 */
public final class McpClient implements AutoCloseable {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final Process process;
    private final BufferedReader reader;
    private final Writer writer;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final String serverName;
    private List<McpToolDefinition> cachedTools;

    /**
     * Launches {@code config}'s command as a subprocess (routed through {@link ProcessLaunch} on
     * Windows), wires up its stdin/stdout as the JSON-RPC transport, starts the background stderr
     * drain, and performs the MCP {@code initialize} handshake before returning, the returned
     * instance is immediately ready for {@link #listTools()}/{@link #callTool}.
     *
     * @param serverName a local label used to disambiguate this server in error messages and (via
     *                    the owning agent field's name) in merged {@code TOOL_CALLING} tool names
     */
    public McpClient(String serverName, McpServerConfig config) {
        this.serverName = serverName;
        try {
            List<String> command = new ArrayList<>();
            command.add(config.getCommand());
            command.addAll(config.getArgs());
            ProcessBuilder builder = new ProcessBuilder(ProcessLaunch.wrapForWindowsIfNeeded(command));
            builder.environment().putAll(config.getEnv());
            this.process = builder.start();
        } catch (IOException e) {
            throw new McpException("Failed to launch MCP server '" + serverName + "': " + e.getMessage(), e);
        }
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        startStderrDrain();
        initialize();
    }

    private void startStderrDrain() {
        Thread thread = new Thread(new StderrDrain(process), "mcp-" + serverName + "-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    /** Continuously reads and discards the server subprocess's stderr on a daemon thread, so diagnostic logging the server writes there can never fill its pipe buffer and block the child on a write. */
    private static final class StderrDrain implements Runnable {
        private final Process process;

        StderrDrain(Process process) {
            this.process = process;
        }

        @Override
        public void run() {
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                while (errReader.readLine() != null) {
                    // Discarded: many MCP servers log diagnostics to stderr; draining prevents the
                    // child process from blocking on a full pipe. Real log forwarding is out of scope.
                }
            } catch (IOException ignored) {
                // Process closed; nothing to drain anymore.
            }
        }
    }

    private void initialize() {
        JSONObject params = new JSONObject();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "jooa");
        clientInfo.put("version", "0.1");
        params.put("clientInfo", clientInfo);
        sendRequest("initialize", params);
        sendNotification("notifications/initialized", new JSONObject());
    }

    /** Fetches the server's {@code tools/list} response and caches it for the lifetime of this client (a fresh {@link McpClient} is needed to pick up server-side tool changes). */
    public synchronized List<McpToolDefinition> listTools() {
        if (cachedTools != null) {
            return cachedTools;
        }
        JSONObject result = sendRequest("tools/list", new JSONObject());
        List<McpToolDefinition> tools = new ArrayList<>();
        JSONArray toolsArray = result.getJSONArray("tools");
        for (int i = 0; i < toolsArray.size(); i++) {
            JSONObject tool = toolsArray.getJSONObject(i);
            JSONObject schema = tool.optJSONObject("inputSchema");
            tools.add(new McpToolDefinition(tool.getString("name"), tool.optString("description", ""), schema != null ? schema : new JSONObject()));
        }
        cachedTools = tools;
        return tools;
    }

    /**
     * Issues a {@code tools/call} request and concatenates every {@code "text"}-typed content
     * block of the response into a single string (jOpenAgent does not surface image/other MCP content
     * block types). Throws {@link McpException} if the server marks the result {@code isError}.
     */
    public synchronized String callTool(String toolName, JSONObject arguments) {
        JSONObject params = new JSONObject();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : new JSONObject());
        JSONObject result = sendRequest("tools/call", params);
        StringBuilder text = new StringBuilder();
        JSONArray content = result.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type", ""))) {
                    text.append(block.optString("text", ""));
                }
            }
        }
        if (result.optBoolean("isError", false)) {
            throw new McpException("MCP tool '" + toolName + "' on server '" + serverName + "' returned an error: " + text);
        }
        return text.toString();
    }

    public String getServerName() {
        return serverName;
    }

    private synchronized JSONObject sendRequest(String method, JSONObject params) {
        int id = nextId.getAndIncrement();
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        writeLine(request.toString());
        return awaitResponse(id);
    }

    private synchronized void sendNotification(String method, JSONObject params) {
        JSONObject notification = new JSONObject();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        writeLine(notification.toString());
    }

    private void writeLine(String json) {
        try {
            writer.write(json);
            writer.write("\n");
            writer.flush();
        } catch (IOException e) {
            throw new McpException("Failed writing to MCP server '" + serverName + "': " + e.getMessage(), e);
        }
    }

    private JSONObject awaitResponse(int expectedId) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JSONObject message;
                try {
                    message = new JSONObject(line);
                } catch (RuntimeException notJson) {
                    continue; // stray non-JSON stdout noise; defensively skip it
                }
                if (!message.has("id")) {
                    continue; // a notification from the server; ignored by this minimal client
                }
                int id = message.optInt("id", -1);
                if (id != expectedId) {
                    continue;
                }
                if (message.has("error")) {
                    JSONObject error = message.getJSONObject("error");
                    throw new McpException("MCP server '" + serverName + "' error " + error.optInt("code", 0) + ": " + error.optString("message", ""));
                }
                JSONObject result = message.optJSONObject("result");
                return result != null ? result : new JSONObject();
            }
        } catch (IOException e) {
            throw new McpException("Failed reading from MCP server '" + serverName + "': " + e.getMessage(), e);
        }
        throw new McpException("MCP server '" + serverName + "' closed its output stream before responding.");
    }

    /** Closes the stdio streams (best-effort) and destroys the server subprocess. */
    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
            // best-effort close
        }
        try {
            reader.close();
        } catch (IOException ignored) {
            // best-effort close
        }
        process.destroy();
    }
}
