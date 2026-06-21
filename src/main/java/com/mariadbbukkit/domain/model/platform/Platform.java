package com.mariadbbukkit.domain.model.platform;

public enum Platform {
    LINUX_X86_64("linux", "x86_64", true),
    LINUX_AARCH64("linux", "aarch64", true),
    WINDOWS_X86_64("windows", "x86_64", false),
    MACOS_X86_64("macos", "x86_64", true),
    MACOS_AARCH64("macos", "aarch64", true),
    UNKNOWN("unknown", "unknown", false);

    private final String os;
    private final String arch;
    private final boolean unixLike;

    Platform(String os, String arch, boolean unixLike) {
        this.os = os;
        this.arch = arch;
        this.unixLike = unixLike;
    }

    public String getOs() { return os; }
    public String getArch() { return arch; }
    public boolean isUnixLike() { return unixLike; }

    public String[] getServerBinaryNames() {
        return unixLike ? new String[]{"mariadbd", "mysqld"} : new String[]{"mariadbd.exe", "mysqld.exe"};
    }
}
