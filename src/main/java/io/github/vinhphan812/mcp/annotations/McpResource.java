package io.github.vinhphan812.mcp.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a method as an MCP exact-resource reader. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpResource {
    /** @return exact resource URI */
    String uri();

    /** @return resource name, or empty string for default handling */
    String name() default "";

    /** @return resource description, or empty string for default handling */
    String description() default "";

    /** @return resource MIME type */
    String mimeType() default "application/json";
}
