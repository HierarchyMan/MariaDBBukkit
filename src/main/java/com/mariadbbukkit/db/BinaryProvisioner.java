package com.mariadbbukkit.db;

import com.mariadbbukkit.util.Platforms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Responsible for making sure the native MariaDB binaries are present on disk.
 * If they are missing, the appropriate pre-built archive is downloaded from
 * Maven Central (the same artifacts MariaDB4j ships) and extracted into the
 * configured base directory.
 *
 * <p>The extraction logic only needs to handle ZIP/JAR archives, because every
 * default download source is a MariaDB4j {@code mariaDB4j-db-*} jar (a zip
 * containing a single top-level directory with {@code bin/}, {@code lib/},
 * {@code share/}, ...).
 */
public final class BinaryProvisioner {

    private static final String MAVEN_BASE =
            "https://repo1.maven.org/maven2/ch/vorburger/mariaDB4j/";

    private final Logger log;
    private final Path baseDir;
    private final boolean downloadEnabled;
    private final String mariadbVersion;
    private final String customUrl;

    public BinaryProvisioner(Logger log, Path baseDir, boolean downloadEnabled,
                             String mariadbVersion, String customUrl) {
        this.log = log;
        this.baseDir = baseDir;
        this.downloadEnabled = downloadEnabled;
        this.mariadbVersion = mariadbVersion;
        this.customUrl = customUrl == null ? "" : customUrl.trim();
    }

    /**
     * Ensures the MariaDB binaries are present, downloading them if necessary.
     *
     * @return the resolved install root (the directory containing {@code bin/})
     * @throws IOException if binaries are missing and cannot be obtained
     */
    public Path ensureBinaries() throws IOException {
        Path existing = findExistingInstall();
        if (existing != null) {
            log.info("MariaDB binaries already present at " + existing.toAbsolutePath());
            return existing;
        }

        if (!downloadEnabled) {
            throw new IOException("MariaDB binaries are missing in " + baseDir.toAbsolutePath()
                    + " and auto-download is disabled. Either enable download.enabled in the"
                    + " config, or place a MariaDB install (with bin/lib/share) there manually.");
        }

        String url = pickDownloadUrl();
        if (url == null) {
            throw new IOException("No default MariaDB binary download is available for platform "
                    + Platforms.current() + ". Set download.source-url in the config to a URL"
                    + " pointing at a compatible MariaDB archive (zip/jar), or install MariaDB"
                    + " on the host and point storage.base-dir at it.");
        }

        Files.createDirectories(baseDir);
        Path archive = download(url);
        Path staged = null;
        try {
            staged = Files.createTempDirectory(baseDir, "extract-");
            extractArchive(archive, staged);
            Path installRoot = resolveInstallRoot(staged);
            moveIntoBase(installRoot);
            makeExecutablesRunnable();
            log.info("MariaDB binaries ready at " + baseDir.toAbsolutePath());
            return baseDir;
        } finally {
            Files.deleteIfExists(archive);
            if (staged != null) {
                try {
                    deleteRecursively(staged);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * @return the install root if {@code bin/<server>} exists under baseDir,
     *         otherwise {@code null}.
     */
    Path findExistingInstall() {
        String[] servers = Platforms.current().serverBinaryNames();
        for (String server : servers) {
            if (Files.exists(baseDir.resolve("bin").resolve(server))) {
                return baseDir;
            }
        }
        if (Files.isDirectory(baseDir)) {
            try (var stream = Files.list(baseDir)) {
                List<Path> dirs = stream.filter(Files::isDirectory).toList();
                for (Path d : dirs) {
                    for (String server : servers) {
                        if (Files.exists(d.resolve("bin").resolve(server))) {
                            return d;
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    String pickDownloadUrl() {
        if (!customUrl.isEmpty()) {
            return customUrl;
        }
        return defaultUrl(Platforms.current(), mariadbVersion);
    }

    /**
     * Default Maven Central MariaDB4j binary artifact URL for a platform.
     * Returns {@code null} for platforms without a published artifact
     * (notably Linux aarch64).
     */
    static String defaultUrl(Platforms p, String version) {
        return switch (p) {
            case LINUX_X86_64 -> artifact("mariaDB4j-db-linux64", version);
            case WINDOWS_X86_64 -> artifact("mariaDB4j-db-winx64", version);
            case MACOS_AARCH64 -> artifact("mariaDB4j-db-macos-arm64", version);
            case MACOS_X86_64 -> artifact("mariaDB4j-db-mac64", version);
            default -> null;
        };
    }

    private static String artifact(String artifactId, String version) {
        return MAVEN_BASE + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
    }

    /** Downloads {@code url} into a temp file inside baseDir, with progress logging. */
    Path download(String urlSpec) throws IOException {
        URL url = new URL(urlSpec);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "MariaDBBukkit/1.0");
        int code = conn.getResponseCode();
        if (code / 100 != 2) {
            throw new IOException("Download failed: HTTP " + code + " for " + urlSpec);
        }
        long total = conn.getContentLengthLong();
        Path out = Files.createTempFile(baseDir, "mariadb-", ".download");
        log.info("Downloading MariaDB binaries from " + urlSpec
                + (total > 0 ? " (" + (total / 1024 / 1024) + " MiB)" : " (size unknown)"));
        long lastLog = 0;
        try (InputStream in = conn.getInputStream();
             OutputStream outBuf = Files.newOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                outBuf.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    long pct = done * 100 / total;
                    if (pct - lastLog >= 10) {
                        lastLog = pct;
                        log.info("Download progress: " + pct + "%");
                    }
                }
            }
            outBuf.flush();
        } catch (IOException e) {
            Files.deleteIfExists(out);
            throw e;
        } finally {
            conn.disconnect();
        }
        log.info("Download complete.");
        return out;
    }

    /** Extracts a zip/jar archive into {@code dest}. */
    void extractArchive(Path archive, Path dest) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                Path target = dest.resolve(entry.getName()).normalize();
                if (!target.startsWith(dest)) {
                    throw new IOException("zip slip: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Finds the directory that contains {@code bin/}. Archives from MariaDB4j
     * contain a single top-level directory; we unwrap it so that baseDir itself
     * is the MariaDB install root.
     */
    Path resolveInstallRoot(Path extracted) throws IOException {
        String[] servers = Platforms.current().serverBinaryNames();
        try (var stream = Files.walk(extracted)) {
            var match = stream.filter(Files::isDirectory)
                    .filter(d -> {
                        Path binDir = d.resolve("bin");
                        if (!Files.isDirectory(binDir)) return false;
                        for (String server : servers) {
                            if (Files.exists(binDir.resolve(server))) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
        }
        throw new IOException("Downloaded archive did not contain a MariaDB install"
                + " (no bin/mariadbd or mysqld found). Check download.mariadb-version / source-url.");
    }

    private void moveIntoBase(Path installRoot) throws IOException {
        if (installRoot.equals(baseDir)) {
            return;
        }
        Files.createDirectories(baseDir);
        try (var stream = Files.list(installRoot)) {
            for (Path p : stream.toList()) {
                Path target = baseDir.resolve(p.getFileName()).normalize();
                if (!target.startsWith(baseDir)) {
                    throw new IOException("refusing unsafe move of " + p);
                }
                Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        deleteRecursively(installRoot);
    }

    private void makeExecutablesRunnable() throws IOException {
        if (!Platforms.current().unixLike) {
            return;
        }
        chmodAll(baseDir.resolve("bin"));
        chmodAll(baseDir.resolve("scripts"));
    }

    private void chmodAll(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        Set<PosixFilePermission> perms = Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
        try (var stream = Files.list(dir)) {
            for (Path exe : stream.toList()) {
                if (!Files.isRegularFile(exe)) continue;
                try {
                    Files.setPosixFilePermissions(exe, perms);
                } catch (UnsupportedOperationException ignored) {
                    runChmod(exe);
                }
            }
        }
    }

    private void runChmod(Path exe) {
        try {
            new ProcessBuilder(Arrays.asList("chmod", "+x", exe.toAbsolutePath().toString()))
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not chmod +x " + exe, e);
        }
    }

    private void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (var stream = Files.list(p)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(p);
    }
}
