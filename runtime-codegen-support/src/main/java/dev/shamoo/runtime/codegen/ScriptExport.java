package dev.shamoo.runtime.codegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a Java method as eligible for a generated script binding. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ScriptExport {
    String value() default "";
}
