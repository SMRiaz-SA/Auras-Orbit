package com.auras.orbit.extensions;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin;
import com.lagradost.cloudstream3.plugins.Plugin;

import org.junit.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class CloudstreamPluginAnnotationCompatibilityTest {
    @Test
    public void pluginMarkerHasRuntimeTypeAnnotationContract() {
        assertTrue("CloudstreamPlugin must be an annotation type",
                CloudstreamPlugin.class.isAnnotation());

        Retention retention = CloudstreamPlugin.class.getAnnotation(Retention.class);
        assertNotNull("CloudstreamPlugin must expose retention metadata", retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());

        Target target = CloudstreamPlugin.class.getAnnotation(Target.class);
        assertNotNull("CloudstreamPlugin must expose target metadata", target);
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, target.value());
    }

    @Test
    public void androidPluginPreservesComposeStabilityFieldForCurrentCs3Bytecode() throws Exception {
        Field stability = Plugin.class.getDeclaredField("$stable");
        assertEquals(int.class, stability.getType());
        assertTrue(Modifier.isPublic(stability.getModifiers()));
        assertTrue(Modifier.isStatic(stability.getModifiers()));
        assertTrue(Modifier.isFinal(stability.getModifiers()));
        assertEquals("use the conservative Compose stability value", 0, stability.getInt(null));
    }
}
