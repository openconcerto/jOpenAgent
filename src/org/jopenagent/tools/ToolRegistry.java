package org.jopenagent.tools;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jopenkit.json.JSONObject;

import org.jopenagent.agentdoc.MemberScanner;
import org.jopenagent.core.Agent;
import org.jopenagent.core.Doc;
import org.jopenagent.json.ObjectBinder;
import org.jopenagent.json.SchemaGenerator;
import org.jopenagent.llm.ToolCall;
import org.jopenagent.llm.ToolSpec;
import org.jopenagent.mcp.McpClient;
import org.jopenagent.mcp.McpToolDefinition;

/**
 * Exposes an agent's ordinary (non-{@code @Generative}, visible) methods as JSON-schema tools a
 * provider's native function-calling API can invoke directly. This is jOpenAgent's explicit
 * complement to the code-as-action mode (org.jopenagent.sandbox), which instead gives generated code
 * direct access to {@code self}. NOOA itself has no such per-method tool-schema abstraction for
 * self-methods (see nooa/skills/nooa-tools-and-skills/SKILL.md: CodeAct-generated code just
 * calls {@code self.method()} directly); jOpenAgent adds this classic ReAct-style alternative because
 * it's explicitly requested and maps naturally onto providers' native tool-calling APIs for
 * generative methods that don't need arbitrary code execution
 * ({@code @UseStrategy(StrategyKind.TOOL_CALLING)}).
 *
 * <p>Also merges in any visible {@link McpClient}-typed field's tools (name-prefixed
 * {@code fieldName__toolName}), so MCP servers attached to an agent are callable from
 * {@code TOOL_CALLING} the same way they're callable directly from {@code CODE_ACT}-generated code.
 */
public final class ToolRegistry {

    private static final String MCP_SEPARATOR = "__";

    private final Agent agent;
    private final Map<String, Method> methodsByName = new LinkedHashMap<>();
    private final Map<String, McpBinding> mcpToolsByName = new LinkedHashMap<>();

    /**
     * Scans {@code agent}'s class once for ordinary visible methods (via
     * {@link org.jopenagent.agentdoc.MemberScanner#ordinaryVisibleMethods}) and for any visible
     * {@link McpClient}-typed field, eagerly calling {@link McpClient#listTools()} on each to merge
     * in its tools under {@code fieldName__toolName}. The resulting tool set is fixed for the life
     * of this registry: a new {@code ToolRegistry} is needed to pick up new/changed MCP tools.
     */
    public ToolRegistry(Agent agent) {
        this.agent = agent;
        for (Method method : MemberScanner.ordinaryVisibleMethods(agent.getClass())) {
            methodsByName.put(method.getName(), method);
        }
        for (Field field : MemberScanner.visibleFields(agent.getClass())) {
            if (!McpClient.class.isAssignableFrom(field.getType())) {
                continue;
            }
            McpClient client = readMcpField(field);
            if (client == null) {
                continue;
            }
            for (McpToolDefinition tool : client.listTools()) {
                mcpToolsByName.put(field.getName() + MCP_SEPARATOR + tool.getName(), new McpBinding(client, tool));
            }
        }
    }

    private McpClient readMcpField(Field field) {
        try {
            field.setAccessible(true);
            return (McpClient) field.get(agent);
        } catch (IllegalAccessException e) {
            throw new ToolInvocationException("Cannot read MCP client field '" + field.getName() + "'", e);
        }
    }

    /**
     * The full JSON-schema tool list to hand a provider's native function-calling API: one
     * {@link ToolSpec} per registered self-method (description from its {@link Doc} annotation, or
     * a generic fallback if absent; parameter schema via {@link SchemaGenerator#forMethodParameters})
     * plus one per merged MCP tool (description/schema straight from the server).
     */
    public List<ToolSpec> toolSpecs() {
        List<ToolSpec> specs = new ArrayList<>();
        for (Method method : methodsByName.values()) {
            Doc doc = method.getAnnotation(Doc.class);
            String description = doc != null ? doc.value() : ("Calls " + method.getName() + " on the agent.");
            specs.add(new ToolSpec(method.getName(), description, SchemaGenerator.forMethodParameters(method)));
        }
        for (Map.Entry<String, McpBinding> entry : mcpToolsByName.entrySet()) {
            McpToolDefinition tool = entry.getValue().tool;
            specs.add(new ToolSpec(entry.getKey(), tool.getDescription(), tool.getInputSchema()));
        }
        return specs;
    }

    public boolean hasTool(String name) {
        return methodsByName.containsKey(name) || mcpToolsByName.containsKey(name);
    }

    /** Invokes the tool named by {@code call}, binding its JSON arguments to the method's real parameter types (or forwarding to the MCP server for MCP-sourced tools). */
    public Object invoke(ToolCall call) {
        McpBinding mcpBinding = mcpToolsByName.get(call.getName());
        if (mcpBinding != null) {
            return mcpBinding.client.callTool(mcpBinding.tool.getName(), call.getArguments());
        }
        Method method = methodsByName.get(call.getName());
        if (method == null) {
            throw new ToolInvocationException("No such tool: '" + call.getName() + "'. Available tools: " + allToolNames());
        }
        JSONObject args = call.getArguments();
        Parameter[] params = method.getParameters();
        Object[] values = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            String name = params[i].getName();
            values[i] = args.has(name) ? ObjectBinder.bind(args.opt(name), params[i].getParameterizedType()) : null;
        }
        try {
            method.setAccessible(true);
            return method.invoke(agent, values);
        } catch (IllegalAccessException e) {
            throw new ToolInvocationException("Cannot invoke tool '" + call.getName() + "': " + e.getMessage(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ToolInvocationException("Tool '" + call.getName() + "' threw an exception: " + cause.getMessage(), cause);
        }
    }

    private List<String> allToolNames() {
        List<String> names = new ArrayList<>(methodsByName.keySet());
        names.addAll(mcpToolsByName.keySet());
        return names;
    }

    /** Pairs a merged-in MCP tool with the {@link McpClient} that owns it, so {@link #invoke} knows which server to dispatch the call to. */
    private static final class McpBinding {
        final McpClient client;
        final McpToolDefinition tool;

        McpBinding(McpClient client, McpToolDefinition tool) {
            this.client = client;
            this.tool = tool;
        }
    }
}
