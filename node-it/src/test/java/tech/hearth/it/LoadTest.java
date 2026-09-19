package tech.hearth.it;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
// TYPE only: loadTestNames discovers carriers with Class.isAnnotationPresent, so a method-level tag would be
// excluded from the integration job and never selected by the load job, i.e. silently stop running.
@Target(ElementType.TYPE)
public @interface LoadTest {
}
