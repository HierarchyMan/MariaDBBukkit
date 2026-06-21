package com.mariadbbukkit.db;

import com.mariadbbukkit.util.Platforms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryProvisionerTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void defaultUrlsAreMappedForSupportedPlatforms() {
        assertEquals(
                "https://repo1.maven.org/maven2/ch/vorburger/mariaDB4j/mariaDB4j-db-linux64/11.4.5/mariaDB4j-db-linux64-11.4.5.jar",
                BinaryProvisioner.defaultUrl(Platforms.LINUX_X86_64, "11.4.5"));
        assertEquals(
                "https://repo1.maven.org/maven2/ch/vorburger/mariaDB4j/mariaDB4j-db-winx64/11.4.5/mariaDB4j-db-winx64-11.4.5.jar",
                BinaryProvisioner.defaultUrl(Platforms.WINDOWS_X86_64, "11.4.5"));
        assertEquals(
                "https://repo1.maven.org/maven2/ch/vorburger/mariaDB4j/mariaDB4j-db-macos-arm64/11.4.5/mariaDB4j-db-macos-arm64-11.4.5.jar",
                BinaryProvisioner.defaultUrl(Platforms.MACOS_AARCH64, "11.4.5"));
    }

    @Test
    void defaultUrlIsNullForUnsupportedPlatforms() {
        assertNull(BinaryProvisioner.defaultUrl(Platforms.LINUX_AARCH64, "11.4.5"));
        assertNull(BinaryProvisioner.defaultUrl(Platforms.UNKNOWN, "11.4.5"));
    }

    @Test
    void customUrlOverridesDefault(@TempDir Path tmp) {
        BinaryProvisioner p = new BinaryProvisioner(LOG, tmp, true, "11.4.5",
                "https://example.com/custom.jar");
        assertEquals("https://example.com/custom.jar", p.pickDownloadUrl());
    }

    @Test
    void findExistingInstallDetectsWrappedLayout(@TempDir Path tmp) throws IOException {
        Path wrapper = tmp.resolve("mariadb-11.4.5-linux-x86_64");
        Path bin = wrapper.resolve("bin");
        Files.createDirectories(bin);
        String server = Platforms.current().serverBinaryNames()[0];
        Files.write(bin.resolve(server), new byte[]{0});

        BinaryProvisioner p = new BinaryProvisioner(LOG, tmp, true, "11.4.5", "");
        Path found = p.findExistingInstall();
        assertNotNull(found, "wrapped install should be detected");
        assertEquals(wrapper.toAbsolutePath(), found.toAbsolutePath());
    }

    @Test
    void extractAndResolveInstallRootFromWrappedZip(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("fake.jar");
        String server = Platforms.current().serverBinaryNames()[0];
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

        BinaryProvisioner p = new BinaryProvisioner(LOG, tmp, true, "11.4.5", "");
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
        BinaryProvisioner p = new BinaryProvisioner(LOG, tmp, true, "11.4.5", "");
        Path staged = Files.createTempDirectory(tmp, "stage-");
        try {
            p.extractArchive(zip, staged);
            assertFalse(true, "expected zip-slip IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("zip slip"));
        }
    }

    @Test
    void platformDetectionDoesNotThrow() {
        assertNotNull(Platforms.current());
    }
}
