package com.teng.app.gastosai.entity;

/** Lifecycle of a user's subscription. Only {@link #ACTIVE} and {@link #TRIAL} grant a paid plan's features. */
public enum SubscriptionStatus {
    ACTIVE,
    TRIAL,
    INACTIVE,
    EXPIRED,
    CANCELLED;

    public boolean grantsAccess() {
        return this == ACTIVE || this == TRIAL;
    }
}
