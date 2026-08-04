package org.jopenagent.core;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.jopenagent.core.strategy.StrategyKind;

/** Resolved metadata for one invocation of a {@code @Generative} method, built by {@link GenerationEngine}. */
public final class CallContext {

    private final Agent agent;
    private final Method method;
    private final Object[] arguments;
    private final String instruction;
    private final Type returnType;
    private final int maxRetries;
    private final StrategyKind strategyKind;

    CallContext(Agent agent, Method method, Object[] arguments, String instruction, Type returnType, int maxRetries, StrategyKind strategyKind) {
        this.agent = agent;
        this.method = method;
        this.arguments = arguments;
        this.instruction = instruction;
        this.returnType = returnType;
        this.maxRetries = maxRetries;
        this.strategyKind = strategyKind;
    }

    /** A copy of this context with a different instruction text, used by {@link org.jopenagent.core.strategy.ReflexionStrategy} to inject feedback between attempts. */
    public CallContext withInstruction(String newInstruction) {
        return new CallContext(agent, method, arguments, newInstruction, returnType, maxRetries, strategyKind);
    }

    public Agent getAgent() {
        return agent;
    }

    public Method getMethod() {
        return method;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public String getInstruction() {
        return instruction;
    }

    public Type getReturnType() {
        return returnType;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public StrategyKind getStrategyKind() {
        return strategyKind;
    }

    /**
     * The method's parameters matched positionally against the values passed into {@code
     * generate(...)}. Requires the "Store information about method parameters (-parameters)"
     * compiler flag; without it, parameter names are unavailable at runtime and this throws with
     * a message explaining how to enable it.
     */
    public List<ParameterBinding> parameterBindings() {
        Parameter[] params = method.getParameters();
        List<ParameterBinding> result = new ArrayList<>(params.length);
        for (int i = 0; i < params.length; i++) {
            Parameter param = params[i];
            if (!param.isNamePresent()) {
                throw new IllegalStateException("Parameter names are not retained for " + method + ". Enable 'Store information about "
                        + "method parameters (-parameters)' in Project Properties > Java Compiler in Eclipse so @Generative methods can "
                        + "render their real argument names into the prompt.");
            }
            Object value = i < arguments.length ? arguments[i] : null;
            result.add(new ParameterBinding(param.getName(), param.getParameterizedType(), value));
        }
        return result;
    }

    /** One {@code @Generative} method parameter paired with the value passed into {@code generate(...)}, as produced by {@link #parameterBindings()}. */
    public static final class ParameterBinding {
        private final String name;
        private final Type type;
        private final Object value;

        public ParameterBinding(String name, Type type, Object value) {
            this.name = name;
            this.type = type;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public Type getType() {
            return type;
        }

        public Object getValue() {
            return value;
        }
    }
}
