package com.lagradost.cloudstream3.utils;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orbit-owned implementation of CloudStream's small UiText value API.
 * Resource lookup is deliberately scoped to the Context supplied by the caller.
 */
public abstract class UiText {
    private UiText() {
        super();
    }

    public final String asString(Context context) {
        if (context == null) {
            throw new NullPointerException("context");
        }

        if (this instanceof DynamicString) {
            return ((DynamicString) this).getValue();
        }
        if (this instanceof StringResource) {
            return ((StringResource) this).asStringResource(context);
        }

        // The private constructor keeps this branch unreachable for callers.
        throw new IllegalStateException("Unsupported UiText value");
    }

    /** Matches the pinned Kotlin data class, including its component and copy API. */
    public static final class DynamicString extends UiText {
        private final String value;

        public DynamicString(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            this.value = value;
        }

        public final String getValue() {
            return value;
        }

        public final String component1() {
            return value;
        }

        public final DynamicString copy(String value) {
            return new DynamicString(value);
        }

        /** Java-visible equivalent of Kotlin's defaulted copy() call. */
        public final DynamicString copy() {
            return new DynamicString(value);
        }

        public static DynamicString copy$default(
                DynamicString self,
                String value,
                int mask,
                Object marker
        ) {
            if (self == null) {
                throw new NullPointerException("self");
            }
            if ((mask & 1) != 0) {
                value = self.value;
            }
            return self.copy(value);
        }

        @Override
        public String toString() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof DynamicString)) return false;
            DynamicString that = (DynamicString) other;
            return value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }

    /** Matches the pinned StringResource constructor, accessors, and value behavior. */
    public static final class StringResource extends UiText {
        private final int resId;
        private final List<Object> args;

        public StringResource(int resId, List<Object> args) {
            if (args == null) {
                throw new NullPointerException("args");
            }
            this.resId = resId;
            this.args = args;
        }

        public final int getResId() {
            return resId;
        }

        public final List<Object> getArgs() {
            return args;
        }

        private String asStringResource(Context context) {
            String template = context.getString(resId);
            if (args.isEmpty()) {
                return template;
            }

            Object[] resolvedArgs = new Object[args.size()];
            for (int index = 0; index < args.size(); index++) {
                Object arg = args.get(index);
                resolvedArgs[index] = arg instanceof UiText
                        ? ((UiText) arg).asString(context)
                        : arg;
            }
            return String.format(Locale.getDefault(), template, resolvedArgs);
        }

        @Override
        public String toString() {
            List<String> renderedArgs = new ArrayList<>(args.size());
            for (Object arg : args) {
                if (arg == null) {
                    // Kotlin's it::class throws on null, preserving that failure shape.
                    throw new NullPointerException("arg");
                }
                String className = kotlin.jvm.JvmClassMappingKt
                        .getKotlinClass(arg.getClass())
                        .toString();
                renderedArgs.add("(" + className + " = " + arg + ")");
            }
            return "resId = " + resId + "\nargs = " + renderedArgs;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof StringResource)) return false;
            StringResource that = (StringResource) other;
            return resId == that.resId && args.equals(that.args);
        }

        @Override
        public int hashCode() {
            int result = resId;
            result = 31 * result + args.hashCode();
            return result;
        }
    }
}
