package com.lagradost.cloudstream3.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.UnaryOperator;

import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.markers.KMutableList;

/**
 * Orbit-authored mutable counterpart to {@link AtomicList}, matching the
 * pinned JVM inheritance and marker interfaces. Element operations synchronize
 * on this list; use {@link #withLock(kotlin.jvm.functions.Function0)} for iteration.
 */
public final class AtomicMutableList<E> extends AtomicList<E> implements List<E>, KMutableList {
    private final List<E> mutableDelegate;

    public AtomicMutableList() {
        this(new ArrayList<E>());
    }

    /** Keeps the supplied mutable delegate live, as the pinned collection contract does. */
    public AtomicMutableList(List<E> delegate) {
        super(delegate);
        this.mutableDelegate = delegate;
    }

    /** Kotlin default-argument constructor bridge for delegate = mutableListOf(). */
    public AtomicMutableList(List<E> delegate, int mask, DefaultConstructorMarker marker) {
        this((mask & 0x1) != 0 ? new ArrayList<E>() : delegate);
    }

    @Override public boolean add(E value) {
        synchronized (this) { return mutableDelegate.add(value); }
    }

    @Override public void add(int index, E value) {
        synchronized (this) { mutableDelegate.add(index, value); }
    }

    @Override public boolean addAll(Collection<? extends E> values) {
        synchronized (this) { return mutableDelegate.addAll(values); }
    }

    @Override public boolean addAll(int index, Collection<? extends E> values) {
        synchronized (this) { return mutableDelegate.addAll(index, values); }
    }

    @Override public void clear() {
        synchronized (this) { mutableDelegate.clear(); }
    }

    @Override public Iterator<E> iterator() { return mutableDelegate.iterator(); }
    @Override public ListIterator<E> listIterator() { return mutableDelegate.listIterator(); }
    @Override public ListIterator<E> listIterator(int index) { return mutableDelegate.listIterator(index); }
    @Override public List<E> subList(int fromIndex, int toIndex) { return mutableDelegate.subList(fromIndex, toIndex); }

    @Override public final E remove(int index) {
        return removeAt(index);
    }

    public E removeAt(int index) {
        synchronized (this) { return mutableDelegate.remove(index); }
    }

    @Override public boolean remove(Object value) {
        synchronized (this) { return mutableDelegate.remove(value); }
    }

    @Override public boolean removeAll(Collection<?> values) {
        synchronized (this) { return mutableDelegate.removeAll(values); }
    }

    @Override public boolean retainAll(Collection<?> values) {
        synchronized (this) { return mutableDelegate.retainAll(values); }
    }

    @Override public void replaceAll(UnaryOperator<E> operator) {
        Objects.requireNonNull(operator, "operator");
        synchronized (this) { mutableDelegate.replaceAll(operator); }
    }

    @Override public void sort(Comparator<? super E> comparator) {
        synchronized (this) { mutableDelegate.sort(comparator); }
    }

    @Override public E set(int index, E value) {
        synchronized (this) { return mutableDelegate.set(index, value); }
    }
}
