package org.jopenagent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.Test;

import org.jopenagent.core.AgentConfig;
import org.jopenagent.llm.ToolCall;
import org.jopenagent.llm.ToolSpec;
import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;

class ToolRegistryTest {

    private SupportAgent newAgent() {
        return new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
    }

    @Test
    void exposesOrdinaryMethodsButNotGenerativeOnes() {
        ToolRegistry registry = new ToolRegistry(newAgent());
        List<ToolSpec> specs = registry.toolSpecs();

        assertTrue(registry.hasTool("ticketBacklog"));
        assertTrue(registry.hasTool("ticketBacklogTimesTwo"));
        assertFalse(registry.hasTool("classify"));
        assertFalse(registry.hasTool("priority"));
        assertFalse(registry.hasTool("doubleBacklogViaCode"));

        boolean foundDoc = false;
        for (ToolSpec spec : specs) {
            if (spec.getName().equals("ticketBacklog")) {
                assertEquals("Returns how many tickets are currently in the backlog.", spec.getDescription());
                foundDoc = true;
            }
        }
        assertTrue(foundDoc);
    }

    @Test
    void invokesToolAndReturnsRealResult() {
        SupportAgent agent = newAgent();
        ToolRegistry registry = new ToolRegistry(agent);

        Object result = registry.invoke(new ToolCall("call-1", "ticketBacklog", new JSONObject()));

        assertEquals(42, result);
    }

    @Test
    void invokingUnknownToolThrows() {
        ToolRegistry registry = new ToolRegistry(newAgent());
        assertThrows(ToolInvocationException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                registry.invoke(new ToolCall("call-1", "doesNotExist", new JSONObject()));
            }
        });
    }
}
