package com.mariadbbukkit.util;

import java.util.Locale;

/**
 * Detects the host operating system and CPU architecture so the plugin can
 * fetch the correct pre-built MariaDB binaries at runtime.
 */
public enum Platforms {
    LINUX_X86_64("linux", "x86_64", true),
    LINUX_AARCH64("linux", "aarch64", true),
    WINDOWS_X86_64("windows", "x86_64", false),
    MACOS_X86_64("macos", "x86_64", true),
    MACOS_AARCH64("macos", "aarch64", true),
    UNKNOWN("unknown", "unknown", false);

    public final String os;
    public final String arch;
    public final boolean unixLike;

    Platforms(String os, String arch, boolean unixLike) {
        this.os = os;
        this.arch = arch;
        this.unixLike = unixLike;
    }

    public static Platforms current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean aarch64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
        if (os.contains("nux") || os.contains("nix")) {
            if (aarch64) return LINUX_AARCH64;
            if (x64) return LINUX_X86_64;
        } else if (os.contains("win")) {
            if (x64) return WINDOWS_X86_64;
        } else if (os.contains("mac") || os.contains("darwin")) {
            if (aarch64) return MACOS_AARCH64;
            if (x64) return MACOS_X86_64;
        }
        return UNKNOWN;
    }

    public String[] serverBinaryNames() {
        return unixLike ? new String[]{"mariadbd", "mysqld"} : new String[]{"mariadbd.exe", "mysqld.exe"};
    }

    /**
     * Termux (Android) detection. Termux runs on bionic libc, so the glibc
     * binaries shipped by MariaDB4j cannot run there; instead the plugin uses
     * the Termux-native {@code mariadb} apt package. Detection is based on the
     * presence of the Termux prefix directory.
     *
     * @return the Termux prefix path (e.g. {@code /data/data/com.termux/files/usr})
     *         if running inside Termux, otherwise {@code null}
     */
    public static String termuxPrefix() {
        String env = System.getenv("PREFIX");
        if (env != null && env.endsWith("/com.termux/files/usr")
                && new java.io.File(env, "bin/mariadbd").exists()) {
            return env;
        }
        // Fallback: hard-coded Android path (works regardless of env vars).
        java.io.File fixed = new java.io.File("/data/data/com.termux/files/usr");
        if (fixed.isDirectory() && new java.io.File(fixed, "bin/mariadbd").exists()) {
            return fixed.getAbsolutePath();
        }
        return null;
    }
}
