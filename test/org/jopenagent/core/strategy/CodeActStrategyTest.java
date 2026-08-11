package org.jopenagent.core.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.Test;

import org.jopenagent.core.AgentConfig;
import org.jopenagent.llm.ToolCall;
import org.jopenagent.sandbox.SandboxConfig;
import org.jopenagent.sandbox.SandboxMode;
import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;

/**
 * Exercises the real JShell-backed sandbox end to end (not mocked), requires the host JVM to
 * have been started with {@code --add-modules jdk.jshell}.
 */
class CodeActStrategyTest {

    @Test
    void executesGeneratedCodeAgainstLiveSelfThenReturnsResult() {
        MockLlmClient llm = new MockLlmClient();

        JSONObject executeArgs = new JSONObject();
        executeArgs.put("code", "int backlog = self.ticketBacklog(); System.out.println(backlog);");
        llm.enqueueToolCall(new ToolCall("call-1", "execute_java", executeArgs));

        JSONObject returnArgs = new JSONObject();
        returnArgs.put("value", 84);
        llm.enqueueToolCall(new ToolCall("call-2", "return_result", returnArgs));

        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        int result = agent.doubleBacklogViaCode();

        assertEquals(84, result);
        assertEquals(2, llm.callCount());
        String toolResultFedBack = llm.getRequests().get(1).getMessages().get(llm.getRequests().get(1).getMessages().size() - 1).getContent();
        assertTrue(toolResultFedBack.contains("42"), toolResultFedBack);
        assertTrue(toolResultFedBack.contains("stdout"), toolResultFedBack);
    }

    @Test
    void generatedCodeCallingTheActiveGenerativeMethodIsRejectedBeforeExecution() {
        MockLlmClient llm = new MockLlmClient();

        // If this code actually reached JShell, the nested doubleBacklogViaCode() call would
        // re-enter the CodeAct loop and drain the mock's queue, so the test passing at all
        // proves the guard short-circuited before execution.
        JSONObject recursiveArgs = new JSONObject();
        recursiveArgs.put("code", "int total = self.doubleBacklogViaCode(); System.out.println(total);");
        llm.enqueueToolCall(new ToolCall("call-1", "execute_java", recursiveArgs));

        JSONObject returnArgs = new JSONObject();
        returnArgs.put("value", 84);
        llm.enqueueToolCall(new ToolCall("call-2", "return_result", returnArgs));

        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).build());

        int result = agent.doubleBacklogViaCode();

        assertEquals(84, result);
        String systemPrompt = llm.getRequests().get(0).getSystemPrompt();
        assertTrue(systemPrompt.contains("You are executing `doubleBacklogViaCode`"), systemPrompt);
        String toolResultFedBack = llm.getRequests().get(1).getMessages().get(llm.getRequests().get(1).getMessages().size() - 1).getContent();
        assertTrue(toolResultFedBack.contains("REJECTED"), toolResultFedBack);
        assertTrue(toolResultFedBack.contains("recursively"), toolResultFedBack);
    }

    @Test
    void outOfProcessSandboxModeExecutesGeneratedCodeInAChildJvmViaTheStrategy() {
        MockLlmClient llm = new MockLlmClient();

        JSONObject executeArgs = new JSONObject();
        executeArgs.put("code", "int backlog = self.ticketBacklog(); System.out.println(backlog);");
        llm.enqueueToolCall(new ToolCall("call-1", "execute_java", executeArgs));

        JSONObject returnArgs = new JSONObject();
        returnArgs.put("value", 84);
        llm.enqueueToolCall(new ToolCall("call-2", "return_result", returnArgs));

        SandboxConfig outOfProcess = SandboxConfig.builder().mode(SandboxMode.OUT_OF_PROCESS).timeoutMillis(15_000L).build();
        SupportAgent agent = new SupportAgent(AgentConfig.builder().llmClient(llm).sandboxConfig(outOfProcess).build());

        int result = agent.doubleBacklogViaCode();

        assertEquals(84, result);
        String toolResultFedBack = llm.getRequests().get(1).getMessages().get(llm.getRequests().get(1).getMessages().size() - 1).getContent();
        assertTrue(toolResultFedBack.contains("42"), toolResultFedBack);
        assertTrue(toolResultFedBack.contains("stdout"), toolResultFedBack);
    }
}
