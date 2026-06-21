package com.mariadbbukkit.infrastructure.provision.mariadb;

import com.mariadbbukkit.domain.model.database.DatabaseConfig;
import com.mariadbbukkit.domain.model.database.DatabaseType;
import com.mariadbbukkit.domain.model.platform.Platform;
import com.mariadbbukkit.domain.model.platform.PlatformDetector;
import com.mariadbbukkit.infrastructure.platform.SystemPlatformDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MariaDBBinaryProvisionerTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static class MockPlatformDetector implements PlatformDetector {
        private final Platform platform;

        public MockPlatformDetector(Platform platform) {
            this.platform = platform;
        }

        @Override
        public Platform current() {
            return platform;
        }

        @Override
        public String termuxPrefix() {
            return null;
        }
    }

    private DatabaseConfig createMockConfig(Path tmp, String version, String customUrl) {
        return new DatabaseConfig(
                DatabaseType.MARIADB,
                new DatabaseConfig.Port(3306),
                new DatabaseConfig.Credentials("db", "user", "pass"),
                new DatabaseConfig.Storage(tmp, tmp, tmp),
                new DatabaseConfig.Pool(10, 2, 30000L, 600000L, 1800000L),
                new DatabaseConfig.Security(true),
                new DatabaseConfig.Termux(true, null),
                new DatabaseConfig.Download(true, version, customUrl)
        );
    }

    @Test
    void defaultUrlsAreMappedForSupportedPlatforms() {
        assertEquals(
                "https://repo1.maven.org/maven2/ch/vorburger/mariaDB4j/mariaDB4j-db-linux64/11.4.5/mariaDB4j-db-linux64-11.4.5.jar",
                MariaDBBinaryProvisioner.defaultUrl(Platform.LINUX_X86_64, "11.4.5"));
        assertEquals(
                "https://repo1.maven.org/maven2/ch/vorburger/mariaDB4j/mariaDB4j-db-winx64/11.4.5/mariaDB4j-db-winx64-11.4.5.jar",
                MariaDBBinaryProvisioner.defaultUrl(Platform.WINDOWS_X86_64, "11.4.5"));
        assertEquals(
                "https://repo1.maven.org/maven2/ch/vorburger/mariaDB4j/mariaDB4j-db-macos-arm64/11.4.5/mariaDB4j-db-macos-arm64-11.4.5.jar",
                MariaDBBinaryProvisioner.defaultUrl(Platform.MACOS_AARCH64, "11.4.5"));
    }

    @Test
    void defaultUrlIsNullForUnsupportedPlatforms() {
        assertNull(MariaDBBinaryProvisioner.defaultUrl(Platform.LINUX_AARCH64, "11.4.5"));
        assertNull(MariaDBBinaryProvisioner.defaultUrl(Platform.UNKNOWN, "11.4.5"));
    }

    @Test
    void customUrlOverridesDefault(@TempDir Path tmp) {
        var detector = new MockPlatformDetector(Platform.WINDOWS_X86_64);
        MariaDBBinaryProvisioner p = new MariaDBBinaryProvisioner(LOG, detector);
        var config = createMockConfig(tmp, "11.4.5", "https://example.com/custom.jar");
        assertEquals("https://example.com/custom.jar", p.pickDownloadUrl(config));
    }

    @Test
    void findExistingInstallDetectsWrappedLayout(@TempDir Path tmp) throws IOException {
        Path wrapper = tmp.resolve("mariadb-11.4.5-linux-x86_64");
        Path bin = wrapper.resolve("bin");
        Files.createDirectories(bin);
        
        var detector = new SystemPlatformDetector();
        String server = detector.current().getServerBinaryNames()[0];
        Files.write(bin.resolve(server), new byte[]{0});

        MariaDBBinaryProvisioner p = new MariaDBBinaryProvisioner(LOG, detector);
        Path found = p.findExistingInstall(tmp);
        assertNotNull(found, "wrapped install should be detected");
        assertEquals(wrapper.toAbsolutePath(), found.toAbsolutePath());
    }

    @Test
    void extractAndResolveInstallRootFromWrappedZip(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("fake.jar");
        var detector = new SystemPlatformDetector();
        String server = detector.current().getServerBinaryNames()[0];
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("mariadb-11.4.5/bin/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("mariadb-11.4.5/bin/" + server));
            zos.write(new byte[]{1, 2, 3});
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("mariadb-11.4.5/lib/libfoo.so"));
            zos.write(new byte[]{9});
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("mariadb-11.4.5/share/foo"));
            zos.write(new byte[]{8});
            zos.closeEntry();
        }

        MariaDBBinaryProvisioner p = new MariaDBBinaryProvisioner(LOG, detector);
        Path staged = Files.createTempDirectory(tmp, "stage-");
        p.extractArchive(zip, staged);
        Path root = p.resolveInstallRoot(staged);
        assertEquals(staged.resolve("mariadb-11.4.5"), root);
        assertTrue(Files.exists(root.resolve("bin").resolve(server)));
        assertTrue(Files.exists(root.resolve("lib/libfoo.so")));
    }

    @Test
    void extractRejectsZipSlip(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("evil.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("../../escape.bin"));
            zos.write(new byte[]{0});
            zos.closeEntry();
        }
        var detector = new SystemPlatformDetector();
        MariaDBBinaryProvisioner p = new MariaDBBinaryProvisioner(LOG, detector);
        Path staged = Files.createTempDirectory(tmp, "stage-");
        try {
            p.extractArchive(zip, staged);
            fail("expected zip-slip IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("zip slip"));
        }
    }

    @Test
    void platformDetectionDoesNotThrow() {
        assertNotNull(new SystemPlatformDetector().current());
    }
}
