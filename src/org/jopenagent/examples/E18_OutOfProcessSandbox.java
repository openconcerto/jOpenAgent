package org.jopenagent.examples;

import java.util.ArrayList;
import java.util.List;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Doc;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.core.strategy.StrategyKind;
import org.jopenagent.sandbox.CodeExecutor;
import org.jopenagent.sandbox.SandboxConfig;
import org.jopenagent.sandbox.SandboxMode;
import org.jopenagent.sandbox.SandboxResult;
import org.jopenagent.sandbox.remote.OutOfProcessCodeSandbox;

/**
 * Same {@code CODE_ACT} task as {@link E08_CodeAsAction}, but with
 * {@link SandboxMode#OUT_OF_PROCESS} configured: generated code runs in a genuinely separate
 * child JVM ({@code org.jopenagent.sandbox.remote.SandboxWorkerMain}) instead of sharing this one, and
 * {@code self.someMethod(...)} calls are transparently forwarded back over a loopback socket (see
 * {@code OutOfProcessCodeSandbox}, {@code SelfStubGenerator}, {@code RemoteSelfDispatch}).
 *
 * <p>{@code main()} then demonstrates the actual benefit directly (bypassing the LLM, so the
 * point is deterministic rather than dependent on a model choosing to write an infinite loop): a
 * tight {@code while (true) {}} handed to the in-process sandbox would hang forever, because
 * {@code jshell.stop()} can only ask a running snippet to yield at its next safepoint, and a loop
 * with no method calls or allocations inside never reaches one. The out-of-process sandbox kills
 * the entire child process on timeout instead, which always works.
 */
@SystemPrompt("You are a data-processing agent.")
public class E18_OutOfProcessSandbox extends Agent {

    private final List<Integer> orderTotalsCents = new ArrayList<>();

    public E18_OutOfProcessSandbox(AgentConfig config) {
        super(config);
        orderTotalsCents.add(1299);
        orderTotalsCents.add(499);
        orderTotalsCents.add(2599);
    }

    @Doc("Returns the list of order totals, in cents.")
    public List<Integer> orderTotalsCents() {
        return new ArrayList<>(orderTotalsCents);
    }

    /**
     * Identical {@code CODE_ACT} capability to {@link E08_CodeAsAction#averageOrderDollars()};
     * what differs is {@link #main}'s {@link SandboxConfig}, which routes the generated code
     * through the out-of-process child-JVM sandbox instead of the default in-process one.
     */
    @Generative(value = "Compute the average order total in whole dollars (rounded), by writing code that reads self.orderTotalsCents().",
            strategy = StrategyKind.CODE_ACT)
    public int averageOrderDollars() {
        return generate();
    }

    public static void main(String[] args) {
        SandboxConfig outOfProcess = SandboxConfig.builder().mode(SandboxMode.OUT_OF_PROCESS).timeoutMillis(10_000L).build();
        AgentConfig config = AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).sandboxConfig(outOfProcess).build();
        E18_OutOfProcessSandbox agent = new E18_OutOfProcessSandbox(config);

        System.out.println("Average order total (dollars), computed in a child JVM: " + agent.averageOrderDollars());

        System.out.println();
        System.out.println("Now proving the hard-kill timeout directly (no LLM involved):");
        try (CodeExecutor sandbox = new OutOfProcessCodeSandbox(agent, 2000L)) {
            long start = System.currentTimeMillis();
            SandboxResult result = sandbox.execute("while (true) { }");
            long elapsedMillis = System.currentTimeMillis() - start;
            System.out.println("  status: " + result.getStatus() + " (after " + elapsedMillis + "ms, not left hanging forever)");
        }
    }
}
