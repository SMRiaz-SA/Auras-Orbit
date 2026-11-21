package com.lagradost.cloudstream3.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.markers.KMappedMarker;

/**
 * Orbit-authored synchronized read-only list surface matching the public JVM
 * contract inspected in recloudstream/cloudstream pre-release 5024ace2922a148d38df2a816b483b529789cf58.
 * No upstream implementation body is copied; the upstream source is GPL-3.0.
 * Iterators are intentionally not held under the monitor; use {@link #withLock(Function0)}
 * when a complete iteration must not race with mutations.
 */
public class AtomicList<E> implements List<E>, KMappedMarker {
    private final List<E> delegate;

    public AtomicList() {
        this(Collections.<E>emptyList());
    }

    @SuppressWarnings("unchecked")
    public AtomicList(List<? extends E> delegate) {
        this.delegate = (List<E>) Objects.requireNonNull(delegate, "delegate");
    }

    /** Kotlin default-argument constructor bridge for delegate = emptyList(). */
    public AtomicList(List<? extends E> delegate, int mask, kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this((mask & 0x1) != 0 ? Collections.<E>emptyList() : delegate);
    }

    public final <R> R withLock(Function0<? extends R> block) {
        Objects.requireNonNull(block, "block");
        synchronized (this) {
            return block.invoke();
        }
    }

    public final AtomicList<E> filter(Function1<? super E, Boolean> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        ArrayList<E> result = new ArrayList<>();
        synchronized (this) {
            for (E item : delegate) if (Boolean.TRUE.equals(predicate.invoke(item))) result.add(item);
        }
        return new AtomicList<>(result);
    }

    public final AtomicList<E> distinctBy(Function1<? super E, ?> selector) {
        Objects.requireNonNull(selector, "selector");
        ArrayList<E> result = new ArrayList<>();
        Set<Object> keys = new LinkedHashSet<>();
        synchronized (this) {
            for (E item : delegate) if (keys.add(selector.invoke(item))) result.add(item);
        }
        return new AtomicList<>(result);
    }

    public final AtomicList<E> plus(E element) {
        synchronized (this) {
            ArrayList<E> result = new ArrayList<>(delegate);
            result.add(element);
            return new AtomicList<>(result);
        }
    }

    public final AtomicList<E> plus(Collection<? extends E> elements) {
        Objects.requireNonNull(elements, "elements");
        synchronized (this) {
            ArrayList<E> result = new ArrayList<>(delegate);
            result.addAll(elements);
            return new AtomicList<>(result);
        }
    }

    @Override public final int size() { synchronized (this) { return delegate.size(); } }
    public int getSize() { return size(); }
    @Override public boolean isEmpty() { synchronized (this) { return delegate.isEmpty(); } }
    @Override public boolean contains(Object value) { synchronized (this) { return delegate.contains(value); } }
    @Override public boolean containsAll(Collection<?> values) { synchronized (this) { return delegate.containsAll(values); } }
    @Override public E get(int index) { synchronized (this) { return delegate.get(index); } }
    @Override public int indexOf(Object value) { synchronized (this) { return delegate.indexOf(value); } }
    @Override public int lastIndexOf(Object value) { synchronized (this) { return delegate.lastIndexOf(value); } }

    @Override public Iterator<E> iterator() { return delegate.iterator(); }
    @Override public ListIterator<E> listIterator() { return delegate.listIterator(); }
    @Override public ListIterator<E> listIterator(int index) { return delegate.listIterator(index); }
    @Override public List<E> subList(int fromIndex, int toIndex) { return delegate.subList(fromIndex, toIndex); }

    @Override public Object[] toArray() { synchronized (this) { return delegate.toArray(); } }
    @Override public <T> T[] toArray(T[] array) { synchronized (this) { return delegate.toArray(array); } }

    @Override public boolean add(E value) { throw new UnsupportedOperationException("AtomicList is read-only"); }
    @Override public void add(int index, E value) { throw new UnsupportedOperationException("AtomicList is read-only"); }
    @Override public boolean addAll(Collection<? extends E> values) { throw new UnsupportedOperationException("AtomicList is read-only"); }
    @Override public boolean addAll(int index, Collection<? extends E> values) { throw new UnsupportedOperationException("AtomicList is read-only"); }
    @Override public void clear() { throw new UnsupportedOperationException("AtomicList is read-only"); }
    @Override public E remove(int index) { throw new UnsupportedOperationException("AtomicList is read-only"); }
    @Override public boolean remove(Object value) { throw new UnsupportedOperationException("AtomicList is read-only"); }
    @Override public boolean removeAll(Collection<?> values) { throw new UnsupportedOperationException("AtomicList is read-only"); }
    @Override public boolean retainAll(Collection<?> values) { throw new UnsupportedOperationException("AtomicList is read-only"); }
    @Override public E set(int index, E value) { throw new UnsupportedOperationException("AtomicList is read-only"); }
    @Override public void replaceAll(UnaryOperator<E> operator) { throw new UnsupportedOperationException("AtomicList is read-only"); }
    @Override public void sort(Comparator<? super E> comparator) { throw new UnsupportedOperationException("AtomicList is read-only"); }

    /** Java 21 List convenience methods are declared explicitly for the pinned JVM surface. */
    public void addFirst(E value) { add(0, value); }
    public void addLast(E value) { add(value); }
    public E removeFirst() { return remove(0); }
    public E removeLast() { return remove(size() - 1); }
}
