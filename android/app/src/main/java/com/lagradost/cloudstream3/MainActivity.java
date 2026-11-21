package com.lagradost.cloudstream3;

import com.lagradost.cloudstream3.utils.Event;

/** Descriptor facade with a stable after-plugins-loaded event; lifecycle timing is owned by 2D. */
public class MainActivity extends android.app.Activity {
    private MainActivity() { super(); }
    public static final com.lagradost.cloudstream3.MainActivity.Companion Companion = new com.lagradost.cloudstream3.MainActivity.Companion();

    public static class Companion {
        private static final Event<Boolean> AFTER_PLUGINS_LOADED_EVENT = new Event<>();
        private static final Event<Boolean> RELOAD_HOME_EVENT = new Event<>();

        private Companion() { super(); }
        public Event<Boolean> getAfterPluginsLoadedEvent() {
            return AFTER_PLUGINS_LOADED_EVENT;
        }

        public Event<Boolean> getReloadHomeEvent() {
            return RELOAD_HOME_EVENT;
        }
    }
}
