package org.jopenagent.core.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.jopenagent.core.AgentConfig;
import org.jopenagent.llm.ToolCall;
import org.jopenagent.testsupport.Category;
import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;
import org.jopenagent.testsupport.TicketClassification;
import org.jopenagent.trace.JsonTraceExporter;
import org.jopenagent.trace.Tracer;

class ToolCallingStrategyTest {

    @AfterEach
    void cleanup() {
        Tracer.clear();
        Tracer.removeAllExporters();
    }

    @Test
    void callsToolThenReturnsFinalResult() {
        MockLlmClient llm = new MockLlmClient();
        llm.enqueueToolCall(new ToolCall("call-1", "ticketBacklog", new JSONObject()));

        JSONObject finalArgs = new JSONObject();
        finalArgs.put("category", "GENERAL");
        finalArgs.put("rationale", "just a question");
        llm.enqueueToolCall(new ToolCall("call-2", "return_result", finalArgs));

        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        TicketClassification result = agent.classifyWithTools("What are your hours?");

        assertEquals(Category.GENERAL, result.category());
        assertEquals(2, llm.callCount());
        // The tool result from the first call should have been fed back before the final answer.
        assertEquals("42", llm.getRequests().get(1).getMessages().get(llm.getRequests().get(1).getMessages().size() - 1).getContent());
    }

    @Test
    void toolExecutionIsTraced() {
        JsonTraceExporter exporter = new JsonTraceExporter();
        Tracer.addExporter(exporter);

        MockLlmClient llm = new MockLlmClient();
        llm.enqueueToolCall(new ToolCall("call-1", "ticketBacklog", new JSONObject()));
        JSONObject finalArgs = new JSONObject();
        finalArgs.put("category", "GENERAL");
        finalArgs.put("rationale", "just a question");
        llm.enqueueToolCall(new ToolCall("call-2", "return_result", finalArgs));
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        agent.classifyWithTools("What are your hours?");

        JSONObject root = exporter.getCaptured().get(0);
        boolean foundToolExecution = false;
        org.jopenkit.json.JSONArray children = root.getJSONArray("children");
        for (int i = 0; i < children.size(); i++) {
            JSONObject child = children.getJSONObject(i);
            if (child.getString("kind").equals("tool_execution")) {
                foundToolExecution = true;
                assertEquals("ticketBacklog", child.getString("name"));
                assertEquals("42", child.getJSONObject("attributes").getString("result"));
            }
        }
        assertTrue(foundToolExecution);
    }

    @Test
    void secondCallOnSameAgentSeesHistoryFromFirstCall() {
        MockLlmClient llm = new MockLlmClient();
        JSONObject firstFinalArgs = new JSONObject();
        firstFinalArgs.put("category", "GENERAL");
        firstFinalArgs.put("rationale", "hours question");
        llm.enqueueToolCall(new ToolCall("call-1", "return_result", firstFinalArgs));

        JSONObject secondFinalArgs = new JSONObject();
        secondFinalArgs.put("category", "BILLING");
        secondFinalArgs.put("rationale", "invoice question");
        llm.enqueueToolCall(new ToolCall("call-2", "return_result", secondFinalArgs));

        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());
        agent.classifyWithTools("What are your hours?");
        // No intermediate tool call happened (direct return_result), so only the user-summary and
        // result entries are recorded (no transcript-note entry).
        assertEquals(2, agent.history.size());

        agent.classifyWithTools("Why was I billed twice?");

        // The second request's conversation must be prefixed with what was recorded from the first call.
        boolean sawFirstCallInHistory = false;
        for (org.jopenagent.llm.LlmMessage message : llm.getRequests().get(1).getMessages()) {
            if (message.getContent() != null && message.getContent().contains("What are your hours?")) {
                sawFirstCallInHistory = true;
            }
        }
        assertTrue(sawFirstCallInHistory);
    }

    @Test
    void historyCanBeDisabled() {
        MockLlmClient llm = new MockLlmClient();
        JSONObject finalArgs = new JSONObject();
        finalArgs.put("category", "GENERAL");
        finalArgs.put("rationale", "hours question");
        llm.enqueueToolCall(new ToolCall("call-1", "return_result", finalArgs));

        SupportAgent agent = new SupportAgent(
                AgentConfig.builder().llmClient(llm).historyConfig(org.jopenagent.core.history.HistoryConfig.builder().enabled(false).build()).build());

        agent.classifyWithTools("What are your hours?");

        assertEquals(0, agent.history.size());
    }
}
