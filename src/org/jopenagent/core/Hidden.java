package org.jopenagent.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Hides a field or ordinary method from {@code doc(self)} rendering, the code-as-action sandbox's
 * exec scope, and tools-via-self schema generation. Mirrors NOOA's visible-by-default model
 * (see nooa/AGENTS.md "Visibility"): public members are visible unless annotated {@code @Hidden}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD })
public @interface Hidden {
}
