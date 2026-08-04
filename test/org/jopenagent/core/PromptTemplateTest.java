package org.jopenagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;

class PromptTemplateTest {

    private SupportAgent newAgent() {
        return new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
    }

    @Test
    void expandsFieldAccess() {
        SupportAgent agent = newAgent();
        String result = PromptTemplate.expand("Hello {self.teamName}!", agent);
        assertEquals("Hello Support!", result);
    }

    @Test
    void expandsNoArgMethodCall() {
        SupportAgent agent = newAgent();
        String result = PromptTemplate.expand("Backlog is {self.ticketBacklog()}.", agent);
        assertEquals("Backlog is 42.", result);
    }

    @Test
    void expandsChainedMethodThenField() {
        SupportAgent agent = newAgent();
        String result = PromptTemplate.expand("Doubled: {self.ticketBacklogTimesTwo()}", agent);
        assertEquals("Doubled: 84", result);
    }

    @Test
    void systemPromptIsExpandedFromClassAnnotation() {
        SupportAgent agent = newAgent();
        String prompt = PromptBuilder.systemPrompt(agent);
        assertEquals(true, prompt.startsWith("You are a support triage agent for Support."));
    }

    @Test
    void docSelfLiteralExpandsToIntrospectorOutput() {
        SupportAgent agent = newAgent();
        String result = PromptTemplate.expand("API:\n{doc(self)}", agent);
        assertEquals(true, result.contains("classify"));
        assertEquals(true, result.contains("ticketBacklog"));
    }

    @Test
    void arbitraryExpressionsAreRejected() {
        SupportAgent agent = newAgent();
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                PromptTemplate.expand("{1 + 1}", agent);
            }
        });
    }
}
