package com.auras.orbit.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Standalone probe for the horizontal RecyclerView virtualization used by provider shelves. */
@RunWith(AndroidJUnit4.class)
public final class HomeProviderRailVirtualizationInstrumentationTest {
    private static final int LARGE_SHELF_TITLE_COUNT = 25_000;
    private static final int MAX_INITIALIZED_HOLDERS = 64;

    @Test
    public void horizontalRailDoesNotCreateAViewForEveryTitle() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicInteger initializedHolders = new AtomicInteger();
        AtomicInteger attachedChildren = new AtomicInteger();
        ProbeAdapter adapter = new ProbeAdapter(initializedHolders);

        instrumentation.runOnMainSync(() -> {
            Context context = instrumentation.getTargetContext();
            RecyclerView rail = new RecyclerView(context);
            rail.setLayoutManager(new LinearLayoutManager(
                    context, RecyclerView.HORIZONTAL, false));
            rail.setItemAnimator(null);
            rail.setItemViewCacheSize(2);
            rail.setAdapter(adapter);

            float density = context.getResources().getDisplayMetrics().density;
            int width = Math.round(720 * density);
            int height = Math.round(180 * density);
            rail.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            rail.layout(0, 0, width, height);

            attachedChildren.set(rail.getChildCount());
        });

        assertEquals("the adapter still exposes every provider title",
                LARGE_SHELF_TITLE_COUNT, adapter.getItemCount());
        assertTrue("the rail must initialize at least one visible holder",
                initializedHolders.get() > 0);
        assertTrue("the rail must attach at least one visible card",
                attachedChildren.get() > 0);
        assertTrue("initial layout must create only viewport holders; created="
                        + initializedHolders.get(),
                initializedHolders.get() < MAX_INITIALIZED_HOLDERS);
        assertTrue("only viewport holders may be attached; attached=" + attachedChildren.get(),
                attachedChildren.get() < MAX_INITIALIZED_HOLDERS);
    }

    private static final class ProbeAdapter extends RecyclerView.Adapter<ProbeViewHolder> {
        private final AtomicInteger initializedHolders;

        ProbeAdapter(AtomicInteger initializedHolders) {
            this.initializedHolders = initializedHolders;
        }

        @Override public ProbeViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            initializedHolders.incrementAndGet();
            View card = new View(parent.getContext());
            card.setLayoutParams(new RecyclerView.LayoutParams(96, 160));
            return new ProbeViewHolder(card);
        }

        @Override public void onBindViewHolder(ProbeViewHolder holder, int position) { }

        @Override public int getItemCount() { return LARGE_SHELF_TITLE_COUNT; }
    }

    private static final class ProbeViewHolder extends RecyclerView.ViewHolder {
        ProbeViewHolder(View itemView) { super(itemView); }
    }
}
