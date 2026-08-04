package org.jopenagent.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Description text for an ordinary (non-{@code @Generative}) method or field, substituting for
 * javadoc (not reflectable at runtime). Used by {@code org.jopenagent.agentdoc.Introspector} to render
 * {@code doc(self)}-style API summaries and by {@code org.jopenagent.tools.ToolRegistry} as the
 * tool-schema description shown to the LLM.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.FIELD })
public @interface Doc {

    String value();
}
