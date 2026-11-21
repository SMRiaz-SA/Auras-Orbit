package com.auras.orbit.providers;

/** Immutable status for one provider within a Home, Search, or title-details batch. */
public final class ProviderOutcome<T> {
    public enum Status { LOADING, SUCCESS, FAILURE, TIMEOUT, CANCELLED }

    public final ProviderDescriptor provider;
    public final int sourceOrder;
    public final int attempt;
    public final Status status;
    public final T value;
    public final String message;

    ProviderOutcome(ProviderDescriptor provider, int sourceOrder, int attempt,
                    Status status, T value, String message) {
        this.provider = provider;
        this.sourceOrder = sourceOrder;
        this.attempt = attempt;
        this.status = status;
        this.value = value;
        this.message = message == null ? "" : message;
    }

    public boolean isTerminal() { return status != Status.LOADING; }
    public boolean canRetry() {
        return status == Status.FAILURE || status == Status.TIMEOUT || status == Status.CANCELLED;
    }
}
