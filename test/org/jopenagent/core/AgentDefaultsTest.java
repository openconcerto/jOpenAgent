package org.jopenagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.jopenagent.memory.HashingEmbedder;
import org.jopenagent.memory.Memory;
import org.jopenagent.memory.OpenAiEmbedder;
import org.jopenagent.sandbox.SandboxMode;
import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;

class AgentDefaultsTest {

    @Test
    void agentGetsWorkingDefaultsForSkillsAndMemoryWithoutExtraConfig() {
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());

        assertNotNull(agent.skills);
        assertNotNull(agent.memory);
        assertNotNull(agent.history);
        assertEquals("", agent.skills.render());
        assertTrue(agent.memory.getEmbedder() instanceof HashingEmbedder);

        agent.memory.remember("customer asked about refund timing");
        List<Memory> recalled = agent.memory.recall("refund timing question", 3);
        assertFalse(recalled.isEmpty());
    }

    @Test
    void embedderBuilderMethodSwapsTheDefaultEmbedderUsedByMemory() {
        OpenAiEmbedder externalEmbedder = OpenAiEmbedder.forLocalServer("nomic-embed-text", "http://unused.invalid");
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).embedder(externalEmbedder).build());

        assertTrue(agent.memory.getEmbedder() == externalEmbedder);
    }

    @Test
    void embedderBuilderMethodIsIgnoredWhenAFullMemoryManagerIsAlsoSet() {
        OpenAiEmbedder externalEmbedder = OpenAiEmbedder.forLocalServer("nomic-embed-text", "http://unused.invalid");
        org.jopenagent.memory.MemoryManager explicitManager = new org.jopenagent.memory.MemoryManager(new org.jopenagent.memory.InMemoryMemoryStore(), new HashingEmbedder());
        SupportAgent agent = new SupportAgent(
                AgentConfig.builder().llmClient(new MockLlmClient()).embedder(externalEmbedder).memoryManager(explicitManager).build());

        assertTrue(agent.memory == explicitManager);
        assertTrue(agent.memory.getEmbedder() instanceof HashingEmbedder);
    }

    @Test
    void agentDefaultsToInProcessSandboxModeWithAFiveSecondTimeout() {
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());

        assertEquals(SandboxMode.IN_PROCESS, agent.getConfig().getSandboxConfig().getMode());
        assertEquals(5000L, agent.getConfig().getSandboxConfig().getTimeoutMillis());
    }
}
