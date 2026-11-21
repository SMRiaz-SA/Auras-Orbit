package com.lagradost.cloudstream3;

import com.auras.orbit.extensions.OrbitCs3HostServices;
import android.widget.Toast;

/** Orbit-owned, lifecycle-bound activity callback facade. */
public class CommonActivity extends android.app.Activity {
    private CommonActivity() { super(); }
    public static final com.lagradost.cloudstream3.CommonActivity INSTANCE = new com.lagradost.cloudstream3.CommonActivity();
    public android.app.Activity getActivity() {
        return OrbitCs3HostServices.get().getCurrentActivity();
    }
    public static void showToast$default(com.lagradost.cloudstream3.CommonActivity p0, java.lang.String p1, java.lang.Integer p2, int p3, java.lang.Object p4) {
        android.app.Activity activity = p0 == null ? null : p0.getActivity();
        if (activity == null || p1 == null) return;
        int duration = (p2 == null || (p3 & (1 << 1)) != 0) ? Toast.LENGTH_SHORT : p2;
        activity.runOnUiThread(() -> Toast.makeText(activity, p1, duration).show());
    }
}
