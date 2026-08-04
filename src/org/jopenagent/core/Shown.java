package org.jopenagent.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Re-exposes a non-public field or method that would otherwise be hidden by default (mirrors
 * NOOA's {@code @spec(hidden=False)} opt-in for {@code _private}-style methods).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD })
public @interface Shown {
}
