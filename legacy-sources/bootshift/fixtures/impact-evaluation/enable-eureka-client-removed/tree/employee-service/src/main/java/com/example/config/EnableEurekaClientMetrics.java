package com.example.config;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** An application-owned annotation whose name merely begins with the removed one. */
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableEurekaClientMetrics {
}
