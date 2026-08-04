package org.jopenagent.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

/**
 * A tiny fake MCP server (stdio JSON-RPC) used only by tests, launched as a real subprocess so
 * {@link McpClient} is exercised against a genuine wire-protocol peer rather than a mock.
 * Understands {@code initialize}, {@code tools/list} (advertises one "echo" tool), and
 * {@code tools/call}.
 */
public final class FakeMcpServer {

    private FakeMcpServer() {
    }

    public static void main(String[] args) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JSONObject request;
            try {
                request = new JSONObject(line);
            } catch (RuntimeException notJson) {
                continue;
            }
            if (!request.has("id")) {
                continue; // a notification (e.g. notifications/initialized) needs no response
            }
            int id = request.getInt("id");
            String method = request.getString("method");
            JSONObject response = new JSONObject();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", buildResult(method, request));
            out.println(response.toString());
            out.flush();
        }
    }

    private static JSONObject buildResult(String method, JSONObject request) {
        if ("initialize".equals(method)) {
            JSONObject result = new JSONObject();
            result.put("protocolVersion", "2024-11-05");
            result.put("capabilities", new JSONObject());
            JSONObject serverInfo = new JSONObject();
            serverInfo.put("name", "fake-mcp-server");
            serverInfo.put("version", "0.1");
            result.put("serverInfo", serverInfo);
            return result;
        }
        if ("tools/list".equals(method)) {
            JSONObject result = new JSONObject();
            JSONArray tools = new JSONArray();
            JSONObject echoTool = new JSONObject();
            echoTool.put("name", "echo");
            echoTool.put("description", "Echoes the given message back.");
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            JSONObject properties = new JSONObject();
            JSONObject messageProperty = new JSONObject();
            messageProperty.put("type", "string");
            properties.put("message", messageProperty);
            schema.put("properties", properties);
            echoTool.put("inputSchema", schema);
            tools.add(echoTool);
            result.put("tools", tools);
            return result;
        }
        if ("tools/call".equals(method)) {
            JSONObject params = request.getJSONObject("params");
            String name = params.getString("name");
            JSONObject arguments = params.optJSONObject("arguments");
            JSONObject result = new JSONObject();
            JSONArray content = new JSONArray();
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            if ("echo".equals(name)) {
                textBlock.put("text", "echo: " + (arguments != null ? arguments.optString("message", "") : ""));
            } else {
                textBlock.put("text", "unknown tool: " + name);
            }
            content.add(textBlock);
            result.put("content", content);
            result.put("isError", false);
            return result;
        }
        return new JSONObject();
    }
}
