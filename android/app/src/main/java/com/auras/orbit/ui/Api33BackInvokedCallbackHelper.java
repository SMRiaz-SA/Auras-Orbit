package com.auras.orbit.ui;

import android.app.Activity;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.RequiresApi;

/** Keeps API-33 back callback types and construction out of OrbitShellActivity's API-23 path. */
@RequiresApi(33)
final class Api33BackInvokedCallbackHelper {
    private Api33BackInvokedCallbackHelper() { }

    static Object register(Activity activity, Runnable callback) {
        OnBackInvokedCallback platformCallback = callback::run;
        activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, platformCallback);
        return platformCallback;
    }

    static void unregister(Activity activity, Object callback) {
        if (callback == null) return;
        activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                (OnBackInvokedCallback) callback);
    }
}
