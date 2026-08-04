package org.jopenagent.core.strategy;

import org.jopenagent.core.CallContext;

/** Executes one {@code @Generative} method call end-to-end and returns the bound result. */
public interface Strategy {

    Object execute(CallContext ctx);
}
