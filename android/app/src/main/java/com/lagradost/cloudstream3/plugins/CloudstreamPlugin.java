package com.lagradost.cloudstream3.plugins;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marker annotation for plugin implementation classes. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CloudstreamPlugin {
}
