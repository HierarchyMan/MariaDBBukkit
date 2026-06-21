package com.mariadbbukkit.infrastructure.provision.mariadb;

import com.mariadbbukkit.domain.model.database.DatabaseConfig;
import com.mariadbbukkit.domain.model.provision.Provisioner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MariaDBTermuxProvisioner implements Provisioner {

    private final Logger log;

    public MariaDBTermuxProvisioner(Logger log) {
        this.log = log;
    }

    @Override
    public Path ensureBinaries(DatabaseConfig config) throws IOException {
        String prefix = config.getTermux().getPrefix();
        Path mariadbd = Path.of(prefix, "bin", "mariadbd");
        if (Files.exists(mariadbd)) {
            log.info("Termux: using host MariaDB binaries at " + prefix);
            return Path.of(prefix);
        }
        if (!config.getTermux().isAutoInstall()) {
            throw new IOException("Termux MariaDB not installed at " + prefix + " and"
                    + " termux.auto-install is disabled. Run: pkg install mariadb");
        }
        log.info("Termux: mariadbd not found, installing via 'pkg install -y mariadb'...");
        runCommand(prefix, new String[]{"pkg", "install", "-y", "mariadb"}, "pkg install mariadb");
        if (!Files.exists(mariadbd)) {
            throw new IOException("pkg install mariadb did not produce " + mariadbd
                    + ". Install it manually: pkg install mariadb");
        }
        log.info("Termux: MariaDB installed.");
        return Path.of(prefix);
    }

    @Override
    public void ensureDataDir(DatabaseConfig config) throws IOException {
        String prefix = config.getTermux().getPrefix();
        Path dataDir = config.getStorage().getDataDir();
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
        runCommand(prefix, cmd.toArray(new String[0]), "mariadb-install-db");
        log.info("Termux: data directory ready.");
    }

    private void runCommand(String prefix, String[] cmd, String label) throws IOException {
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
