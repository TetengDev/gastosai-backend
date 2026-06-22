package com.teng.app.gastosai.exception;

import com.teng.app.gastosai.entity.FeatureKey;

/** Thrown when an authenticated user attempts to use a feature their plan does not include. */
public class FeatureLockedException extends RuntimeException {

    private final transient FeatureKey feature;

    public FeatureLockedException(FeatureKey feature) {
        super("This feature requires an upgraded plan: " + feature.name());
        this.feature = feature;
    }

    public FeatureLockedException(FeatureKey feature, String message) {
        super(message);
        this.feature = feature;
    }

    public FeatureKey getFeature() {
        return feature;
    }
}
