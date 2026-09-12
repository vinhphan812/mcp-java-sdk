package io.github.vinhphan812.mcp.annotations;

import java.lang.annotation.*;

/** Describes an annotated tool or prompt parameter. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER})
@Repeatable(McpParams.class)
public @interface McpParam {
    /** @return parameter name */
    String name();

    /** @return parameter description */
    String description() default "";

    /** @return whether parameter is required */
    boolean required() default false;

    /** @return JSON schema type */
    String type() default "string";
}
