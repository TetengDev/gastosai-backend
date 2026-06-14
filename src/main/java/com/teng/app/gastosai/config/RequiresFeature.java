package com.teng.app.gastosai.config;

import com.teng.app.gastosai.entity.FeatureKey;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller handler as requiring entitlement to a {@link FeatureKey}. Enforced centrally by
 * {@code FeatureAccessInterceptor}; backend access control never relies on the frontend hiding UI.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFeature {
    FeatureKey value();
}
