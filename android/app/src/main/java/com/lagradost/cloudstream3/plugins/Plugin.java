package com.lagradost.cloudstream3.plugins;

import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.Keep;

import com.auras.orbit.extensions.OrbitCs3HostServices;
import com.lagradost.cloudstream3.actions.VideoClickAction;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Android CS3 plugin base. Orbit supplies resource and configuration callbacks per load. */
public abstract class Plugin extends BasePlugin {
    /**
     * Compose's compiler-generated stability ABI marker from CloudStream's Kotlin Plugin class.
     * Newer CS3 plugin bytecode reads this field while initializing composable plugin classes.
     * Zero is the conservative unstable value, so Compose never skips a changed Plugin instance.
     */
    @Keep
    public static final int $stable = 0;

    private volatile Resources resources;
    private volatile Function1<? super Context, Unit> openSettings;

    public Plugin() { super(); }

    public void load(Context context) throws Throwable {
        load();
    }

    /** Registers an action against this plugin's active load transaction. */
    public final void registerVideoClickAction(VideoClickAction element) {
        OrbitCs3HostServices.registerVideoClickAction(this, element);
    }

    public final Resources getResources() { return resources; }

    public final void setResources(Resources value) { resources = value; }

    public final Function1<? super Context, Unit> getOpenSettings() { return openSettings; }

    public final void setOpenSettings(Function1<? super Context, Unit> callback) { openSettings = callback; }

    /** Returns false when the extension did not contribute a settings callback. */
    public final boolean dispatchOpenSettings(Context context) {
        Function1<? super Context, Unit> callback = openSettings;
        if (callback == null || context == null) return false;
        callback.invoke(context);
        return true;
    }
}
