package org.jopenagent.core.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import org.jopenagent.core.AgentConfig;
import org.jopenagent.testsupport.Category;
import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;
import org.jopenagent.testsupport.TicketClassification;

class ReflexionStrategyTest {

    @Test
    void retriesWithFeedbackUntilSatisfactory() {
        MockLlmClient llm = new MockLlmClient();
        llm.enqueueText("{\"category\": \"GENERAL\", \"rationale\": \"guess\"}"); // attempt 1
        llm.enqueueText("{\"isSatisfactory\": false, \"issues\": [\"wrong category\"], \"suggestions\": [\"re-read for billing terms\"], \"reasoning\": \"mentions invoice\"}"); // critique 1
        llm.enqueueText("{\"category\": \"BILLING\", \"rationale\": \"invoice mentioned\"}"); // attempt 2
        llm.enqueueText("{\"isSatisfactory\": true, \"issues\": [], \"suggestions\": [], \"reasoning\": \"correct now\"}"); // critique 2

        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        TicketClassification result = agent.classifyWithReflexion("I was billed twice for the same invoice.");

        assertEquals(Category.BILLING, result.category());
        assertEquals(4, llm.callCount());
        // The second attempt's prompt must include the first critique's feedback.
        String secondAttemptPrompt = llm.getRequests().get(2).getMessages().get(0).getContent();
        org.junit.jupiter.api.Assertions.assertTrue(secondAttemptPrompt.contains("wrong category"));
    }

    @Test
    void returnsBestEffortAfterMaxIterationsWithoutSatisfaction() {
        MockLlmClient llm = new MockLlmClient();
        llm.enqueueText("{\"category\": \"GENERAL\", \"rationale\": \"attempt 1\"}");
        llm.enqueueText("{\"isSatisfactory\": false, \"issues\": [\"still wrong\"], \"suggestions\": [\"try again\"], \"reasoning\": \"no\"}");
        llm.enqueueText("{\"category\": \"GENERAL\", \"rationale\": \"attempt 2\"}");
        llm.enqueueText("{\"isSatisfactory\": false, \"issues\": [\"still wrong\"], \"suggestions\": [\"try again\"], \"reasoning\": \"no\"}");
        llm.enqueueText("{\"category\": \"TECHNICAL\", \"rationale\": \"attempt 3, final\"}"); // 3rd attempt, no critique call since maxIterations reached

        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        TicketClassification result = agent.classifyWithReflexion("ambiguous ticket");

        assertEquals(Category.TECHNICAL, result.category());
        assertEquals("attempt 3, final", result.rationale());
        assertEquals(5, llm.callCount());
    }
}
