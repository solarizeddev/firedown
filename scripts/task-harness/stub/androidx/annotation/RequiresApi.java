package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
public @interface RequiresApi { int value() default 1; int api() default 1; }
