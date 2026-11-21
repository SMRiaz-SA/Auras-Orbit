package com.auras.orbit.network;

import android.app.Activity;
import android.os.Bundle;

/** Minimal local-only Activity whose lifecycle owns resolver WebViews in the fixture variant. */
public final class WebViewResolverFixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
