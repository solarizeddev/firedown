package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.SOURCE) @Target({ElementType.FIELD,ElementType.METHOD,ElementType.PARAMETER,ElementType.LOCAL_VARIABLE,ElementType.TYPE_USE})
public @interface GuardedBy { String value() default ""; }
