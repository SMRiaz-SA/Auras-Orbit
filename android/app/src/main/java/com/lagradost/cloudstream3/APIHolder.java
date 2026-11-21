package com.lagradost.cloudstream3;

import com.lagradost.cloudstream3.OrbitCs3FacadeBoundary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Orbit-owned provider registry exposed through the sampled CS3 singleton ABI. */
public final class APIHolder {
    public static final APIHolder INSTANCE = new APIHolder();
    private final CopyOnWriteArrayList<MainAPI> allProviders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MainAPI> apis = new CopyOnWriteArrayList<>();
    private volatile Map<String, Integer> apiMap;

    private APIHolder() { }

    public long getUnixTimeMS() { return System.currentTimeMillis(); }
    public long getUnixTime() { return getUnixTimeMS() / 1000L; }

    public String capitalize(String value) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(value, "<this>");
        if (value.isEmpty()) return value;
        char first = value.charAt(0);
        if (!Character.isLowerCase(first)) return value;
        return kotlin.text.CharsKt.titlecase(first, Locale.ROOT) + value.substring(1);
    }

    /** Runs the pinned CloudStream reCAPTCHA token flow using Orbit's bounded HTTP helper. */
    public Object getCaptchaToken(String url, String key, String referer,
                                  kotlin.coroutines.Continuation<? super String> continuation) {
        if (url == null || key == null) return null;
        return OrbitCaptchaToken.getCaptchaToken(url, key, referer, continuation);
    }

    /** Pinned Kotlin suspend/default-argument bridge. */
    public static Object getCaptchaToken$default(APIHolder self, String url, String key, String referer,
            kotlin.coroutines.Continuation<? super String> continuation, int mask, Object marker) {
        return self.getCaptchaToken(url, key, (mask & 4) != 0 ? null : referer, continuation);
    }

    public List<MainAPI> getAllProviders() { return Collections.unmodifiableList(allProviders); }

    public List<MainAPI> getApis() { return Collections.unmodifiableList(apis); }

    public Map<String, Integer> getApiMap() { return apiMap; }

    public void setApiMap(Map<String, Integer> value) {
        apiMap = value;
    }

    public void initAll() {
        for (MainAPI provider : allProviders) provider.init();
        apiMap = null;
    }

    /** Adds a provider by object identity while preserving registration order. */
    public void addPluginMapping(MainAPI provider) {
        if (provider == null) return;
        synchronized (this) {
            if (containsIdentity(allProviders, provider)) return;
            allProviders.add(provider);
            apis.add(provider);
            rebuildApiMapLocked();
        }
    }

    /** Removes only the exact provider object owned by the unloading plugin. */
    public void removePluginMapping(MainAPI provider) {
        if (provider == null) return;
        synchronized (this) {
            removeIdentity(allProviders, provider);
            removeIdentity(apis, provider);
            rebuildApiMapLocked();
        }
    }

    private static boolean containsIdentity(List<MainAPI> values, MainAPI target) {
        for (MainAPI value : values) if (value == target) return true;
        return false;
    }

    private static void removeIdentity(CopyOnWriteArrayList<MainAPI> values, MainAPI target) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index) == target) {
                values.remove(index);
                return;
            }
        }
    }

    public MainAPI getApiFromNameNull(String name) {
        if (name == null) return null;
        synchronized (this) {
            if (apiMap == null) rebuildApiMapLocked();
            Integer index = apiMap.get(name);
            if (index != null && index >= 0 && index < apis.size()) return apis.get(index);
        }
        for (MainAPI provider : allProviders) {
            if (name.equals(provider.getName())) return provider;
        }
        return null;
    }

    public MainAPI getApiFromUrlNull(String url) {
        if (url == null) return null;
        for (MainAPI provider : allProviders) {
            String base = provider.getMainUrl();
            if (url.startsWith(base)) return provider;
        }
        return null;
    }

    private void rebuildApiMap() {
        synchronized (this) { rebuildApiMapLocked(); }
    }

    private void rebuildApiMapLocked() {
        LinkedHashMap<String, Integer> next = new LinkedHashMap<>();
        for (int index = 0; index < apis.size(); index++) {
            String name = apis.get(index).getName();
            if (name != null && !name.isEmpty()) next.put(name, index);
        }
        apiMap = Collections.unmodifiableMap(next);
    }
}
