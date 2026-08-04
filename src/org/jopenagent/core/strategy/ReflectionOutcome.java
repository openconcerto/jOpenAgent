package org.jopenagent.core.strategy;

import java.util.List;

/** Structured self-critique produced by {@link ReflexionStrategy}'s reflection call. */
public record ReflectionOutcome(boolean isSatisfactory, List<String> issues, List<String> suggestions, String reasoning) {
}
