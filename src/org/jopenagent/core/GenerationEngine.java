package org.jopenagent.core;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;

import org.jopenagent.core.strategy.CodeActStrategy;
import org.jopenagent.core.strategy.PredictStrategy;
import org.jopenagent.core.strategy.ReflexionStrategy;
import org.jopenagent.core.strategy.Strategy;
import org.jopenagent.core.strategy.StrategyKind;
import org.jopenagent.core.strategy.ToolCallingStrategy;
import org.jopenagent.trace.Span;
import org.jopenagent.trace.Traced;

/** Orchestrates one {@code @Generative} call: resolves the strategy, wraps it in a trace span, runs it. */
public final class GenerationEngine {

    private static final String RESERVED_PARAMETER_NAME = "reasoning";

    private GenerationEngine() {
    }

    /**
     * Resolves per-call settings that fall back to agent-level defaults (retries, strategy) and
     * packages them with the method/annotation/arguments into a {@link CallContext} for
     * {@link #run(CallContext)}. Also rejects methods that declare a parameter named
     * {@code "reasoning"}, which is reserved for strategy-internal use.
     */
    public static CallContext buildContext(Agent agent, Method method, Generative annotation, Object[] args) {
        validateNoReservedParameterNames(method);
        Type returnType = method.getGenericReturnType();
        int maxRetries = annotation.maxRetries() >= 0 ? annotation.maxRetries() : agent.getConfig().getMaxRetries();
        StrategyKind kind = annotation.strategy() != StrategyKind.INHERIT ? annotation.strategy() : agent.getConfig().getDefaultStrategy();
        return new CallContext(agent, method, args != null ? args : new Object[0], annotation.value(), returnType, maxRetries, kind);
    }

    /**
     * Selects the {@link Strategy} for {@code ctx.getStrategyKind()} and executes it inside a
     * {@code generation} trace span, recording failure on the span (via {@link Traced#fail}) and
     * rethrowing rather than swallowing it.
     */
    public static Object run(CallContext ctx) {
        Strategy strategy = resolveStrategy(ctx.getStrategyKind());
        Method method = ctx.getMethod();
        Span span = Traced.begin("generation", method.getDeclaringClass().getSimpleName() + "." + method.getName());
        try {
            Object result = strategy.execute(ctx);
            Traced.finish(span);
            return result;
        } catch (RuntimeException e) {
            Traced.fail(span, e);
            throw e;
        }
    }

    private static Strategy resolveStrategy(StrategyKind kind) {
        switch (kind) {
            case CODE_ACT:
                return new CodeActStrategy();
            case TOOL_CALLING:
                return new ToolCallingStrategy();
            case REFLEXION:
                return new ReflexionStrategy();
            case PREDICT:
            case INHERIT:
            default:
                return new PredictStrategy();
        }
    }

    private static void validateNoReservedParameterNames(Method method) {
        for (Parameter param : method.getParameters()) {
            if (param.isNamePresent() && RESERVED_PARAMETER_NAME.equals(param.getName())) {
                throw new IllegalStateException(
                        "Parameter name '" + RESERVED_PARAMETER_NAME + "' is reserved on @Generative method " + method + ". Rename it (e.g. to 'rationale').");
            }
        }
    }
}
