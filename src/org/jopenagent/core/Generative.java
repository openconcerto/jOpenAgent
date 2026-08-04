package org.jopenagent.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.jopenagent.core.strategy.StrategyKind;

/**
 * Marks a method whose body delegates to the LLM via {@link Agent#generate(Object...)}.
 * This is jOpenAgent's equivalent of a NOOA method whose body is a bare {@code ...}: the instruction
 * text plays the role of the method's docstring (unavailable at runtime in Java), and the
 * declared return type is the structured-output contract.
 *
 * <pre>
 * {@literal @}Generative("Classify the ticket into one of the known categories.")
 * protected Category classify(String ticketText) {
 *     return generate(ticketText); // trampoline: the interception point
 * }
 * </pre>
 *
 * Any statements before the {@code generate(...)} call run as ordinary Java, the equivalent of
 * NOOA's "pre-ellipsis code" used for prefill/setup.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Generative {

    /** The task instruction (prompt) for this generation method. */
    String value();

    /** Overrides the agent's default max retry count for this method; -1 = use the agent's default. */
    int maxRetries() default -1;

    /** Overrides the agent's default strategy for this method. */
    StrategyKind strategy() default StrategyKind.INHERIT;
}
