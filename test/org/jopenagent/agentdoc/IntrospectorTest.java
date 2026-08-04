package org.jopenagent.agentdoc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.jopenagent.core.AgentConfig;
import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;

class IntrospectorTest {

    @Test
    void describeHidesAnnotatedFieldsAndShowsVisibleOnes() {
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
        String description = agent.describe();

        assertTrue(description.contains("teamName"));
        assertFalse(description.contains("secretApiKey"));
    }

    @Test
    void describeListsGenerativeMethodsWithInstructionText() {
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
        String description = agent.describe();

        assertTrue(description.contains("classify"));
        assertTrue(description.contains("Classify the support ticket"));
    }

    @Test
    void describeListsOrdinaryMethodsWithDocAnnotation() {
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
        String description = agent.describe();

        assertTrue(description.contains("ticketBacklog"));
        assertTrue(description.contains("Returns how many tickets are currently in the backlog."));
    }

    @Test
    void describeExcludesStaticMethods() {
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
        String description = agent.describe();

        assertFalse(description.contains("staticHelperNotAnAgentCapability"));
    }
}
