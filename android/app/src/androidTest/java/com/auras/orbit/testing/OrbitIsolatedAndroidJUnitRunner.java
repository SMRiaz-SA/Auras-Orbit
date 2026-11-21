package com.auras.orbit.testing;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;

import androidx.test.runner.AndroidJUnitRunner;

import java.io.File;

/** Prevents the target app's production Application.onCreate from touching its normal stores. */
public final class OrbitIsolatedAndroidJUnitRunner extends AndroidJUnitRunner {
    private static final String ORBIT_APPLICATION = "com.auras.orbit.OrbitApplication";
    public static final String PRODUCTION_APPLICATION_GATE_FILE_NAME =
            "orbit_saf_cold_start_production_application.gate";

    @Override
    public Application newApplication(ClassLoader classLoader, String className, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (ORBIT_APPLICATION.equals(className)) {
            if (com.auras.orbit.BuildConfig.CS3_CANARY_BUILD) {
                return super.newApplication(classLoader, className, context);
            }
            if ("com.auras.orbit.queuerender".equals(context.getPackageName())) {
                return super.newApplication(classLoader, className, context);
            }
            File productionApplicationGate = new File(context.getFilesDir(),
                    PRODUCTION_APPLICATION_GATE_FILE_NAME);
            if (productionApplicationGate.isFile()) {
                return super.newApplication(classLoader, className, context);
            }
            return Instrumentation.newApplication(Application.class, context);
        }
        return super.newApplication(classLoader, className, context);
    }
}
