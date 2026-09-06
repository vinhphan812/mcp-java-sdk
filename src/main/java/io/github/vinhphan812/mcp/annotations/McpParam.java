package io.github.vinhphan812.mcp.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes an annotated tool or prompt parameter. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER})
@Repeatable(McpParams.class)
public @interface McpParam {
    String name();
    String description() default "";
    boolean required() default false;
    String type() default "string";
}
