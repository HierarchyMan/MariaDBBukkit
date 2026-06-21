package com.mariadbbukkit.infrastructure.platform;

import com.mariadbbukkit.domain.model.platform.Platform;
import com.mariadbbukkit.domain.model.platform.PlatformDetector;

import java.io.File;
import java.util.Locale;

public final class SystemPlatformDetector implements PlatformDetector {

    @Override
    public Platform current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean aarch64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
        if (os.contains("nux") || os.contains("nix")) {
            if (aarch64) return Platform.LINUX_AARCH64;
            if (x64) return Platform.LINUX_X86_64;
        } else if (os.contains("win")) {
            if (x64) return Platform.WINDOWS_X86_64;
        } else if (os.contains("mac") || os.contains("darwin")) {
            if (aarch64) return Platform.MACOS_AARCH64;
            if (x64) return Platform.MACOS_X86_64;
        }
        return Platform.UNKNOWN;
    }

    @Override
    public String termuxPrefix() {
        String env = System.getenv("PREFIX");
        if (env != null && env.endsWith("/com.termux/files/usr")
                && new File(env, "bin/mariadbd").exists()) {
            return env;
        }
        // Fallback: hard-coded Android path (works regardless of env vars).
        File fixed = new File("/data/data/com.termux/files/usr");
        if (fixed.isDirectory() && new File(fixed, "bin/mariadbd").exists()) {
            return fixed.getAbsolutePath();
        }
        return null;
    }
}
