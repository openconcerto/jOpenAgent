package org.jopenagent.eval;

/**
 * One eval case's unit of work: typically an anonymous inner class that calls a
 * {@code @Generative} method on an agent under test and returns its result, e.g.
 * {@code new EvalTask<TicketClassification>() { public TicketClassification run() { return agent.classify(text); } }}.
 * A plain interface rather than {@code java.util.function.Supplier} only to give call sites a
 * name (`run`) that reads naturally at the call site; functionally equivalent otherwise.
 */
public interface EvalTask<T> {
    T run();
}
