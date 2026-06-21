package com.mariadbbukkit.domain.model.platform;

public interface PlatformDetector {
    Platform current();
    String termuxPrefix();
}
