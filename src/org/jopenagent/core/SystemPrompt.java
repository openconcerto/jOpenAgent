package org.jopenagent.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level system prompt for an {@link Agent}, jOpenAgent's equivalent of NOOA's class docstring.
 * Java javadoc comments are stripped by javac and unavailable at runtime, so the instruction text
 * is carried as an annotation value instead.
 *
 * <p>Supports {@code {self.fieldName}} and {@code {self.methodName()}} placeholders, expanded
 * against the live agent instance each time the prompt is built (see {@link PromptTemplate}).
 * If a subclass doesn't declare {@code @SystemPrompt}, the nearest annotated superclass is used
 * (mirroring NOOA's MRO walk for {@code __doc__}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SystemPrompt {

    /** The system prompt template text, expanded via {@link PromptTemplate#expand(String, Object)} before use. */
    String value();
}
