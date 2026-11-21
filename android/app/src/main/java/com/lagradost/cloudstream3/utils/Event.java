package com.lagradost.cloudstream3.utils;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** A thread-safe observer set matching the CloudStream Event<T> API. */
public final class Event<T> {
    private final Set<Function1<? super T, Unit>> observers = new LinkedHashSet<>();

    public Event() {
        super();
    }

    public final int getSize() {
        synchronized (observers) {
            return observers.size();
        }
    }

    public final void plusAssign(Function1<? super T, Unit> observer) {
        Objects.requireNonNull(observer, "observer");
        synchronized (observers) {
            observers.add(observer);
        }
    }

    public final void minusAssign(Function1<? super T, Unit> observer) {
        Objects.requireNonNull(observer, "observer");
        synchronized (observers) {
            observers.remove(observer);
        }
    }

    public final void invoke(T value) {
        synchronized (observers) {
            for (Function1<? super T, Unit> observer : observers) {
                observer.invoke(value);
            }
        }
    }
}
