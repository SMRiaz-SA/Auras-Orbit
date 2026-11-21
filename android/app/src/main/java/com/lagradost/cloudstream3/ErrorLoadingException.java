package com.lagradost.cloudstream3;

import kotlin.jvm.internal.DefaultConstructorMarker;

/** Orbit-owned equivalent of the pinned checked CS3 loading exception. */
public class ErrorLoadingException extends java.lang.Exception {
    /** Kotlin's defaulted nullable message exposes a JVM no-argument constructor. */
    public ErrorLoadingException() { this(null); }

    public ErrorLoadingException(java.lang.String message) { super(message); }

    /** Kotlin default-argument constructor bridge for the nullable message parameter. */
    public ErrorLoadingException(java.lang.String message, int mask, DefaultConstructorMarker marker) {
        this((mask & 1) != 0 ? null : message);
    }
}
