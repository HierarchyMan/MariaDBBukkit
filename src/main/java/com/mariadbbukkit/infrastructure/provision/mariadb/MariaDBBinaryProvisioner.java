package com.mariadbbukkit.infrastructure.provision.mariadb;

import com.mariadbbukkit.domain.model.database.DatabaseConfig;
import com.mariadbbukkit.domain.model.platform.Platform;
import com.mariadbbukkit.domain.model.platform.PlatformDetector;
import com.mariadbbukkit.domain.model.provision.Provisioner;

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

public final class MariaDBBinaryProvisioner implements Provisioner {

    private static final String MAVEN_BASE =
            "https://repo1.maven.org/maven2/ch/vorburger/mariaDB4j/";

    private final Logger log;
    private final PlatformDetector platformDetector;

    public MariaDBBinaryProvisioner(Logger log, PlatformDetector platformDetector) {
        this.log = log;
        this.platformDetector = platformDetector;
    }

    @Override
    public Path ensureBinaries(DatabaseConfig config) throws IOException {
        Path baseDir = config.getStorage().getBaseDir();
        Path existing = findExistingInstall(baseDir);
        if (existing != null) {
            log.info("MariaDB binaries already present at " + existing.toAbsolutePath());
            return existing;
        }

        if (!config.getDownload().isEnabled()) {
            throw new IOException("MariaDB binaries are missing in " + baseDir.toAbsolutePath()
                    + " and auto-download is disabled. Either enable download.enabled in the"
                    + " config, or place a MariaDB install (with bin/lib/share) there manually.");
        }

        String url = pickDownloadUrl(config);
        if (url == null) {
            throw new IOException("No default MariaDB binary download is available for platform "
                    + platformDetector.current() + ". Set download.source-url in the config to a URL"
                    + " pointing at a compatible MariaDB archive (zip/jar), or install MariaDB"
                    + " on the host and point storage.base-dir at it.");
        }

        Files.createDirectories(baseDir);
        Path archive = download(baseDir, url);
        Path staged = null;
        try {
            staged = Files.createTempDirectory(baseDir, "extract-");
            extractArchive(archive, staged);
            Path installRoot = resolveInstallRoot(staged);
            moveIntoBase(baseDir, installRoot);
            makeExecutablesRunnable(baseDir);
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

    @Override
    public void ensureDataDir(DatabaseConfig config) throws IOException {
        // Standard provisioner doesn't need custom data dir initialization (MariaDB4j does it automatically)
    }

    public Path findExistingInstall(Path baseDir) {
        String[] servers = platformDetector.current().getServerBinaryNames();
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

    public String pickDownloadUrl(DatabaseConfig config) {
        String customUrl = config.getDownload().getSourceUrl();
        if (customUrl != null && !customUrl.trim().isEmpty()) {
            return customUrl.trim();
        }
        return defaultUrl(platformDetector.current(), config.getDownload().getMariadbVersion());
    }

    public static String defaultUrl(Platform p, String version) {
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

    private Path download(Path baseDir, String urlSpec) throws IOException {
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

    public void extractArchive(Path archive, Path dest) throws IOException {
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

    public Path resolveInstallRoot(Path extracted) throws IOException {
        String[] servers = platformDetector.current().getServerBinaryNames();
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

    private void moveIntoBase(Path baseDir, Path installRoot) throws IOException {
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

    private void makeExecutablesRunnable(Path baseDir) throws IOException {
        if (!platformDetector.current().isUnixLike()) {
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
