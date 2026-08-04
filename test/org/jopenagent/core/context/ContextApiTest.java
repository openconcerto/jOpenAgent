package org.jopenagent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.jopenagent.core.AgentConfig;
import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;

class ContextApiTest {

    @Test
    void staticContextBlockIsEvaluatedOnceAndCached() {
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
        agent.context.put("plan", "step 1, step 2");

        assertEquals("step 1, step 2", agent.context.get("plan"));
        agent.teamName = "changed"; // must not affect a static block
        assertEquals("step 1, step 2", agent.context.get("plan"));
    }

    @Test
    void dynamicContextBlockReEvaluatesEachTime() {
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
        agent.context.setDynamic("team", "self.teamName");

        assertEquals("Support", agent.context.get("team"));
        agent.teamName = "Escalations";
        assertEquals("Escalations", agent.context.get("team"));
    }

    @Test
    void renderIncludesAllBlocks() {
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
        agent.context.put("plan", "do the thing");
        String rendered = agent.context.render();
        assertTrue(rendered.contains("plan"));
        assertTrue(rendered.contains("do the thing"));
    }
}
