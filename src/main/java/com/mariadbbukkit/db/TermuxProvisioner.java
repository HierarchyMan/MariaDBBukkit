package com.mariadbbukkit.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Termux-specific provisioner. Termux runs on Android's bionic libc, so the
 * glibc MariaDB binaries shipped by MariaDB4j cannot run there. Instead this
 * uses the Termux-native {@code mariadb} apt package, and works around two
 * Termux-specific quirks:
 *
 * <ol>
 *   <li>The {@code mariadb-install-db} script shipped by Termux has a broken
 *       hardcoded path when {@code --basedir} is passed. We invoke it by bare
 *       name on PATH without {@code --basedir}, which hits the script's correct
 *       {@code else} branch (hardcoded Termux prefix).</li>
 *   <li>MariaDB4j's own {@code install()} step passes {@code --basedir}, which
 *       would break. We pre-initialise the data directory ourselves, so
 *       MariaDB4j sees a populated data dir and skips its install step
 *       entirely (see {@code DB.newEmbeddedDB}).</li>
 * </ol>
 */
public final class TermuxProvisioner {

    private final Logger log;
    private final String prefix;
    private final Path dataDir;
    private final boolean autoInstall;

    public TermuxProvisioner(Logger log, String prefix, Path dataDir, boolean autoInstall) {
        this.log = log;
        this.prefix = prefix;
        this.dataDir = dataDir;
        this.autoInstall = autoInstall;
    }

    /** @return the Termux prefix path (install root). */
    public Path ensureBinaries() throws IOException {
        Path mariadbd = Path.of(prefix, "bin", "mariadbd");
        if (Files.exists(mariadbd)) {
            log.info("Termux: using host MariaDB binaries at " + prefix);
            return Path.of(prefix);
        }
        if (!autoInstall) {
            throw new IOException("Termux MariaDB not installed at " + prefix + " and"
                    + " termux.auto-install is disabled. Run: pkg install mariadb");
        }
        log.info("Termux: mariadbd not found, installing via 'pkg install -y mariadb'...");
        runCommand(new String[]{"pkg", "install", "-y", "mariadb"}, "pkg install mariadb");
        if (!Files.exists(mariadbd)) {
            throw new IOException("pkg install mariadb did not produce " + mariadbd
                    + ". Install it manually: pkg install mariadb");
        }
        log.info("Termux: MariaDB installed.");
        return Path.of(prefix);
    }

    /**
     * Pre-initialises the data directory if empty, using the working bare-name
     * invocation of {@code mariadb-install-db} (no {@code --basedir}).
     */
    public void ensureDataDir() throws IOException {
        if (Files.isDirectory(dataDir)) {
            try (var stream = Files.list(dataDir)) {
                if (stream.findAny().isPresent()) {
                    log.info("Termux: data directory already initialised at " + dataDir);
                    return;
                }
            } catch (IOException e) {
                throw new IOException("Could not inspect data dir " + dataDir, e);
            }
        }
        Files.createDirectories(dataDir);
        Path installDb = Path.of(prefix, "bin", "mariadb-install-db");
        List<String> cmd = new ArrayList<>();
        cmd.add(installDb.toAbsolutePath().toString());
        cmd.add("--no-defaults");
        cmd.add("--force");
        cmd.add("--skip-name-resolve");
        cmd.add("--datadir=" + dataDir.toAbsolutePath());
        log.info("Termux: initialising MariaDB data directory at " + dataDir
                + " (bare-name install-db, no --basedir to avoid Termux script bug)");
        runCommand(cmd.toArray(new String[0]), "mariadb-install-db");
        log.info("Termux: data directory ready.");
    }

    private void runCommand(String[] cmd, String label) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("PATH", prefix + "/bin:" + System.getenv("PATH"));
        try {
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            if (code != 0) {
                String dump = new String(out).trim();
                log.log(Level.SEVERE, label + " failed (exit " + code + "):\n" + dump);
                throw new IOException(label + " failed (exit " + code + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(label + " interrupted", e);
        }
    }
}
