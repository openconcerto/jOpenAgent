package org.jopenagent.core;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jopenagent.agentdoc.Introspector;
import org.jopenagent.core.context.ContextApi;
import org.jopenagent.core.history.ConversationHistory;
import org.jopenagent.llm.LlmClient;
import org.jopenagent.memory.MemoryManager;
import org.jopenagent.skills.SkillRegistry;

/**
 * Base class for jOpenAgent agents: the Java analogue of a NOOA {@code class MyAgent(Agent, llm=llm)}.
 * A concrete subclass is a plain Java class where:
 * <ul>
 * <li>typed instance fields are the agent's state (reflectively visible via {@link #describe()},
 * the code-as-action sandbox, and tools-via-self, unless marked {@link Hidden});</li>
 * <li>ordinary methods with real bodies are deterministic capabilities;</li>
 * <li>methods annotated {@link Generative}, ending in a call to {@link #generate(Object...)},
 * are delegated to the LLM.</li>
 * </ul>
 */
public abstract class Agent {

    /** Static/dynamic context blocks rendered into the system prompt (see nooa/AGENTS.md "Context Blocks"). */
    public final ContextApi context;

    /** Persistent cross-call conversation log, read/written by CODE_ACT and TOOL_CALLING strategies and auto-summarized (see org.jopenagent.core.history). */
    public final ConversationHistory history = new ConversationHistory();

    /** Attached skills (see org.jopenagent.skills), packaged capability/prompt bundles, visible to the LLM via {@link PromptBuilder#systemPrompt}. */
    public final SkillRegistry skills;

    /** Cross-session remember/recall API (see org.jopenagent.memory), configurable via {@link AgentConfig#getMemoryManager()}. */
    public final MemoryManager memory;

    private final AgentConfig config;

    /** Shortcut for the common case of only needing to supply an {@link LlmClient}; builds an {@link AgentConfig} with all other settings defaulted. */
    protected Agent(LlmClient llmClient) {
        this(AgentConfig.builder().llmClient(llmClient).build());
    }

    /** Full-control constructor: subclasses that need non-default retries, strategy, history, memory, or sandbox settings call this via {@code super(config)}. */
    protected Agent(AgentConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.context = new ContextApi(this);
        this.skills = new SkillRegistry(this);
        this.memory = config.getMemoryManager();
    }

    public final AgentConfig getConfig() {
        return config;
    }

    public final LlmClient getLlmClient() {
        return config.getLlmClient();
    }

    /**
     * The interception point for a {@code @Generative} method. Call it as that method's body (or
     * its final statement, after any ordinary pre-processing code):
     *
     * <pre>
     * {@literal @}Generative("Classify the ticket.")
     * protected Category classify(String text) {
     *     return generate(text);
     * }
     * </pre>
     *
     * <p>Uses {@link StackWalker} to identify the calling method, reads its {@link Generative}
     * annotation and declared return type via reflection, and dispatches to the configured
     * {@link org.jopenagent.core.strategy.Strategy}. The JVM does not expose a caller's live local
     * variables the way Python's frame introspection does, so whichever of the method's own
     * parameters should be shown to the LLM must be passed explicitly as {@code boundArgs}.
     */
    @SuppressWarnings("unchecked")
    protected final <T> T generate(Object... boundArgs) {
        StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        List<StackWalker.StackFrame> frames = walker.walk(new FrameCollector());
        if (frames.size() < 2) {
            throw new IllegalStateException("generate() must be called from within a method of an Agent subclass.");
        }
        StackWalker.StackFrame callerFrame = frames.get(1);
        Method method = resolveMethod(callerFrame);
        Generative annotation = method.getAnnotation(Generative.class);
        if (annotation == null) {
            throw new IllegalStateException("generate() was called from " + method + ", which is not annotated @Generative. "
                    + "Only call generate(...) from within a method annotated @Generative.");
        }
        CallContext ctx = GenerationEngine.buildContext(this, method, annotation, boundArgs);
        return (T) GenerationEngine.run(ctx);
    }

    private static Method resolveMethod(StackWalker.StackFrame frame) {
        Class<?> declaringClass = frame.getDeclaringClass();
        String methodName = frame.getMethodName();
        Class<?>[] paramTypes = frame.getMethodType().parameterArray();
        Class<?> current = declaringClass;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, paramTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException("Could not resolve calling method " + methodName + " on " + declaringClass);
    }

    /** Limits the {@link StackWalker} walk in {@link #generate(Object...)} to a shallow, bounded slice of frames before materializing them into a list. */
    private static final class FrameCollector implements Function<Stream<StackWalker.StackFrame>, List<StackWalker.StackFrame>> {
        @Override
        public List<StackWalker.StackFrame> apply(Stream<StackWalker.StackFrame> stream) {
            return stream.limit(4).collect(Collectors.toList());
        }
    }

    /** Auto-generated API documentation for this agent, the {@code doc(self)} equivalent. */
    public final String describe() {
        return Introspector.describe(this);
    }
}
