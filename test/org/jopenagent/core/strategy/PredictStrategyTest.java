package org.jopenagent.core.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.GenerationException;
import org.jopenagent.llm.media.Image;
import org.jopenagent.testsupport.Category;
import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;
import org.jopenagent.testsupport.TicketClassification;

class PredictStrategyTest {

    @Test
    void imageParameterIsSentAsMediaNotJsonText() {
        MockLlmClient llm = new MockLlmClient();
        llm.enqueueText("{\"category\": \"TECHNICAL\", \"rationale\": \"broken button in screenshot\"}");
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        Image screenshot = Image.fromBytes(new byte[] { 1, 2, 3, 4 }, "image/png");
        TicketClassification result = agent.classifyFromScreenshot(screenshot);

        assertEquals(Category.TECHNICAL, result.category());
        org.jopenagent.llm.LlmMessage sentMessage = llm.lastRequest().getMessages().get(0);
        assertTrue(sentMessage.hasMedia());
        assertEquals(1, sentMessage.getMedia().size());
        // The base64 image bytes must not have been dumped into the text prompt.
        assertTrue(!sentMessage.getContent().contains("AQIDBA"));
    }

    @Test
    void successfulCallOnFirstAttempt() {
        MockLlmClient llm = new MockLlmClient();
        llm.enqueueText("{\"category\": \"BILLING\", \"rationale\": \"invoice question\"}");
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        TicketClassification result = agent.classify("I was charged twice on my invoice.");

        assertEquals(Category.BILLING, result.category());
        assertEquals(1, llm.callCount());
    }

    @Test
    void invalidJsonTriggersRetryThenSucceeds() {
        MockLlmClient llm = new MockLlmClient();
        llm.enqueueText("this is not json");
        llm.enqueueText("{\"category\": \"TECHNICAL\", \"rationale\": \"server error\"}");
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        TicketClassification result = agent.classify("Getting a 500 error.");

        assertEquals(Category.TECHNICAL, result.category());
        assertEquals(2, llm.callCount());
        // The retry prompt must include the raw bad response and an error explanation.
        String secondPrompt = llm.getRequests().get(1).getMessages().get(0).getContent();
        assertTrue(secondPrompt.contains("this is not json"));
        assertTrue(secondPrompt.contains("Error"));
    }

    @Test
    void exhaustingRetriesThrowsGenerationExceptionWithAllAttempts() {
        MockLlmClient llm = new MockLlmClient();
        llm.enqueueText("nope");
        llm.enqueueText("still nope");
        llm.enqueueText("nope again");
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).maxRetries(3).build());

        GenerationException ex = assertThrows(GenerationException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                agent.classify("anything");
            }
        });

        assertEquals(3, llm.callCount());
        assertEquals(3, ex.getFailedAttempts().size());
    }

    @Test
    void scalarReturnTypeIsUnwrappedFromValueField() {
        MockLlmClient llm = new MockLlmClient();
        llm.enqueueText("{\"value\": 4}");
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        int result = agent.priority("This is on fire.");

        assertEquals(4, result);
    }

    @Test
    void listReturnTypeIsUnwrappedFromValueField() {
        MockLlmClient llm = new MockLlmClient();
        llm.enqueueText("{\"value\": [\"billing\", \"refund\"]}");
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        List<String> result = agent.topics("Refund my invoice please.");

        assertEquals(List.of("billing", "refund"), result);
    }

    static class NonGenerativeCallerAgent extends Agent {
        NonGenerativeCallerAgent(AgentConfig config) {
            super(config);
        }

        Object callGenerateWithoutAnnotation() {
            return generate();
        }
    }

    @Test
    void generateCalledOutsideGenerativeMethodThrowsIllegalState() {
        NonGenerativeCallerAgent agent = new NonGenerativeCallerAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                agent.callGenerateWithoutAnnotation();
            }
        });
    }

    static class ReservedParamAgent extends Agent {
        ReservedParamAgent(AgentConfig config) {
            super(config);
        }

        @Generative("test")
        String bad(String reasoning) {
            return generate(reasoning);
        }
    }

    @Test
    void reservedParameterNameIsRejected() {
        ReservedParamAgent agent = new ReservedParamAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                agent.bad("x");
            }
        });
    }
}
