package org.jopenagent.trace.atif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.jopenagent.core.AgentConfig;
import org.jopenagent.llm.ToolCall;
import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;
import org.jopenagent.trace.Tracer;

class AtifExporterTest {

    @AfterEach
    void cleanup() {
        Tracer.clear();
        Tracer.removeAllExporters();
    }

    @Test
    void toolCallingProducesAgentAndDeterministicDispatchSteps() {
        AtifExporter exporter = new AtifExporter().withSessionId("session-1");
        Tracer.addExporter(exporter);

        MockLlmClient llm = new MockLlmClient();
        llm.enqueueToolCall(new ToolCall("call-1", "ticketBacklog", new JSONObject()));
        JSONObject finalArgs = new JSONObject();
        finalArgs.put("category", "GENERAL");
        finalArgs.put("rationale", "hours question");
        llm.enqueueToolCall(new ToolCall("call-2", "return_result", finalArgs));
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        agent.classifyWithTools("What are your hours?");

        JSONObject trajectory = exporter.getCaptured().get(0);
        assertEquals("ATIF-v1.7", trajectory.getString("schema_version"));
        assertEquals("session-1", trajectory.getString("session_id"));

        JSONArray steps = trajectory.getJSONArray("steps");
        assertEquals("user", steps.getJSONObject(0).getString("source"));

        boolean foundAgentLlmStep = false;
        boolean foundDeterministicDispatchStep = false;
        for (int i = 0; i < steps.size(); i++) {
            JSONObject step = steps.getJSONObject(i);
            if (step.getString("source").equals("agent") && step.getInt("llm_call_count") == 1) {
                foundAgentLlmStep = true;
            }
            if (step.getString("source").equals("agent") && step.getInt("llm_call_count") == 0 && step.has("observation")) {
                foundDeterministicDispatchStep = true;
                String content = step.getJSONObject("observation").getJSONArray("results").getJSONObject(0).getString("content");
                assertEquals("42", content);
            }
        }
        assertTrue(foundAgentLlmStep, "expected at least one agent llm_call step");
        assertTrue(foundDeterministicDispatchStep, "expected the ticketBacklog tool call to appear as a deterministic-dispatch observation step");
    }
}
