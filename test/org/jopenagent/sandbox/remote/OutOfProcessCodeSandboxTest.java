package org.jopenagent.sandbox.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.jopenagent.core.AgentConfig;
import org.jopenagent.sandbox.SandboxResult;
import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;

/**
 * Exercises {@link OutOfProcessCodeSandbox} end to end against a genuinely separate child JVM
 * (not mocked): this is precisely the property the in-process {@link org.jopenagent.sandbox.CodeSandbox}
 * cannot offer: a real process boundary that a hard kill can always tear down, even mid tight
 * infinite loop.
 */
class OutOfProcessCodeSandboxTest {

    private static SupportAgent newAgent() {
        return new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
    }

    @Test
    @Timeout(30)
    void executesGeneratedCodeInAChildProcessAndInvokesSelfBackOnTheHost() {
        SupportAgent agent = newAgent();
        try (OutOfProcessCodeSandbox sandbox = new OutOfProcessCodeSandbox(agent, 15_000L)) {
            SandboxResult result = sandbox.execute("int backlog = self.ticketBacklog(); backlog * 2;");

            assertEquals(SandboxResult.Status.OK, result.getStatus());
            assertEquals("84", result.getLastValue());
        }
    }

    @Test
    @Timeout(30)
    void capturesStdoutPrintedInTheChildProcess() {
        SupportAgent agent = newAgent();
        try (OutOfProcessCodeSandbox sandbox = new OutOfProcessCodeSandbox(agent, 15_000L)) {
            SandboxResult result = sandbox.execute("System.out.println(\"hello from worker\");");

            assertEquals(SandboxResult.Status.OK, result.getStatus());
            assertTrue(result.getStdout().contains("hello from worker"), result.getStdout());
        }
    }

    @Test
    @Timeout(30)
    void rejectsDisallowedCodeWithoutEvenReachingTheChildJvmsJshell() {
        SupportAgent agent = newAgent();
        try (OutOfProcessCodeSandbox sandbox = new OutOfProcessCodeSandbox(agent, 15_000L)) {
            SandboxResult result = sandbox.execute("System.exit(1);");

            assertEquals(SandboxResult.Status.REJECTED, result.getStatus());
        }
    }

    @Test
    @Timeout(30)
    void aTightInfiniteLoopIsActuallyKilledByProcessTermination() {
        SupportAgent agent = newAgent();
        try (OutOfProcessCodeSandbox sandbox = new OutOfProcessCodeSandbox(agent, 2_000L)) {
            long start = System.currentTimeMillis();
            SandboxResult result = sandbox.execute("while (true) { }");
            long elapsed = System.currentTimeMillis() - start;

            assertEquals(SandboxResult.Status.TIMED_OUT, result.getStatus());
            // Real proof this is a process kill, not a cooperative stop request: it must return
            // close to the configured timeout, not hang for the lifetime of the infinite loop.
            assertTrue(elapsed < 10_000L, "expected a hard kill well under 10s, took " + elapsed + "ms");
        }
    }

    @Test
    @Timeout(45)
    void transparentlyRestartsAFreshWorkerAfterATimeoutKill() {
        SupportAgent agent = newAgent();
        try (OutOfProcessCodeSandbox sandbox = new OutOfProcessCodeSandbox(agent, 2_000L)) {
            SandboxResult timedOut = sandbox.execute("while (true) { }");
            assertEquals(SandboxResult.Status.TIMED_OUT, timedOut.getStatus());

            SandboxResult afterRestart = sandbox.execute("self.ticketBacklog();");
            assertEquals(SandboxResult.Status.OK, afterRestart.getStatus());
            assertEquals("42", afterRestart.getLastValue());
        }
    }
}
