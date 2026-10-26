package io.github.kfagent.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在普通方法上，配合 {@link MethodTool#of(Object, String)} 把它包装成一个 {@link Tool}。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolMethod {
    /** 工具名，默认用方法名。 */
    String name() default "";
    /** 工具描述，LLM 据此决定何时调用。 */
    String description();
}
