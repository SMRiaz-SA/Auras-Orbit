package com.auras.orbit.network;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.auras.orbit.extensions.CloudStreamExtensionManager;
import com.auras.orbit.extensions.OrbitCs3HostServices;

/** Variant-only Application for resolver instrumentation; never starts OrbitShellActivity. */
public final class WebViewResolverFixtureApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        OrbitCs3HostServices hostServices = OrbitCs3HostServices.install(
                this, new CloudStreamExtensionManager(this));
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) {
                hostServices.onActivityStarted(activity);
            }
            @Override public void onActivityResumed(Activity activity) {
                hostServices.onActivityResumed(activity);
            }
            @Override public void onActivityPaused(Activity activity) {
                hostServices.onActivityPaused(activity);
            }
            @Override public void onActivityStopped(Activity activity) {
                hostServices.onActivityStopped(activity);
            }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) {
                hostServices.onActivityStopped(activity);
            }
        });
    }
}
