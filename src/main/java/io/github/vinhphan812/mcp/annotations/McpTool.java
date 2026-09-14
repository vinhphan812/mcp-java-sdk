package io.github.vinhphan812.mcp.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a method as an MCP tool endpoint. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpTool {
    /** @return tool name, or empty string for default handling */
    String name() default "";

    /** @return tool description, or empty string for default handling */
    String description() default "";

    /**
     * Declares the tool's output schema as a JSON string.
     * When absent or empty, no outputSchema is emitted in tools/list.
     * @return output schema as a JSON string (e.g. a static final field containing JSON)
     */
    String outputSchema() default "";

    /**
     * Authorisation scopes required to call this tool.
     * Set via {@link io.github.vinhphan812.mcp.api.spi.McpAuthorization} to enforce.
     * @return array of scope names, or empty array for no scopes
     */
    String[] scopes() default {};

    /**
     * Whether this tool requires explicit user confirmation before execution.
     * Set via {@link io.github.vinhphan812.mcp.api.spi.McpAuthorization} to enforce.
     * @return true to require confirmation
     */
    boolean confirmationRequired() default false;
}
