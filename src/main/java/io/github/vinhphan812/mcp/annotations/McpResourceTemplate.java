package io.github.vinhphan812.mcp.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a method as an MCP resource-template reader. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpResourceTemplate {
    /** @return resource URI template */
    String uriTemplate();

    /** @return resource template name, or empty string for default handling */
    String name() default "";

    /** @return resource template description, or empty string for default handling */
    String description() default "";

    /** @return resource template MIME type */
    String mimeType() default "application/json";
}
