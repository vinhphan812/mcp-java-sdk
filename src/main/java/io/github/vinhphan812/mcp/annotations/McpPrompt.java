package io.github.vinhphan812.mcp.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a method as an MCP prompt provider. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpPrompt {
    /** @return prompt name, or empty string for default handling */
    String name() default "";

    /** @return prompt description, or empty string for default handling */
    String description() default "";
}
