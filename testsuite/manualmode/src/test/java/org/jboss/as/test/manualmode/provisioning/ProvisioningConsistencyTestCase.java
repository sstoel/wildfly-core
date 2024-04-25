/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.manualmode.provisioning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.logging.Logger;
import org.junit.Test;

/**
 * Validates that provisioning using wildfly-maven-plugin and the channel manifest produced by
 * building the standard dist produces an installation consistent with what is produced by
 * the galleon-maven-plugin when it produced the standard dist.
 * <p>
 * The purpose of this test is to demonstrate consistency between the two provisioning methods,
 * thus supporting the concept that tests run against an installation provisioned one way are
 * meaningful for an installation provisioned the other way.
 */
public class ProvisioningConsistencyTestCase {

    private static final Logger log = Logger.getLogger(ProvisioningConsistencyTestCase.class);

    private static final String INSTALLATION = ".installation";
    private static final String PROVISIONING = ".wildfly-maven-plugin-provisioning.xml";
    private static final Path JBOSS_HOME = resolveJBossHome();
    private static final Path CHANNEL_INSTALLATION = JBOSS_HOME.getParent().resolve("wildfly-from-channel");
    private static final Path INSTALLATION_METADATA = CHANNEL_INSTALLATION.resolve(INSTALLATION);
    private static final Path PROVISIONING_XML = CHANNEL_INSTALLATION.resolve(PROVISIONING);
    private static final Path SOURCE_HOME = JBOSS_HOME.getParent().getParent().getParent().getParent();
    private static final Path DIST_INSTALLATION = SOURCE_HOME.resolve("dist").resolve("target").resolve(getDistDir());

    private static Path resolveJBossHome() {
        try {
            return new File(System.getenv("JBOSS_HOME")).getCanonicalFile().toPath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getDistDir() {
        return "wildfly-core-dist-" + System.getProperty("standard.dist.version");
    }

    private static File getDistFile(Path channelPath, boolean exists, boolean directory, List<String> errors) {
        Path relative = CHANNEL_INSTALLATION.relativize(channelPath);
        System.out.println("Getting dist file for relative path " + relative);
        Path path = DIST_INSTALLATION.resolve(relative);
        File test = path.toFile();
        File result = null;
        if (exists) {
            if (!test.exists()) {
                errors.add(String.format("%s %s does not exist", (directory ? "Directory" : "File"), path));
            } else if (test.isDirectory() != directory) {
                errors.add(String.format("%s %s %s a directory",
                        (directory ? "Directory" : "File"),
                        path,
                        (directory ? "is" : "is not")));
            } else {
                // Test file is acceptable; return it
                result = test;
            }
        } else if (test.exists()) {
            errors.add(String.format("%s %s should not exist", (directory ? "Directory" : "File"), path));
        }
        return result;
    }

    /**
     * Compare the contents of an installation created using wildfly-maven-plugin
     * and a channel with the standard dist.
     *
     * @throws IOException if a problem occurs walking the file tree of the
     */
    @Test
    public void testInstallationEquivalence() throws IOException {
        final AtomicReference<Path> installationMetadata = new AtomicReference<>();
        final List<String> errors = new ArrayList<>();
        Files.walkFileTree(CHANNEL_INSTALLATION, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                log.trace("Testing " + dir);
                if (dir.equals(CHANNEL_INSTALLATION)) {
                    File distRoot = getDistFile(dir, true, true, errors);
                    Set<String> channelChildren = new TreeSet<>(Arrays.asList(Objects.requireNonNull(dir.toFile().list())));
                    channelChildren.remove(PROVISIONING);
                    Set<String> distChildren = new TreeSet<>(Arrays.asList(Objects.requireNonNull(distRoot.list())));
                    assertEquals(dir.toString(), channelChildren, distChildren);
                    return FileVisitResult.CONTINUE;
                } else if (dir.equals(INSTALLATION_METADATA)) {
                    installationMetadata.set(dir);
                    File[] files = dir.toFile().listFiles();
                    if (files == null || files.length == 0) {
                        errors.add("No children for " + dir);
                    }
                    File dist = getDistFile(dir, true, true, errors);
                    files = dist.listFiles();
                    if (files != null && files.length > 0) {
                        errors.add(String.format("%s has unexpected files: %s", dist, Arrays.asList(files)));
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                } else {
                    File distDir = getDistFile(dir, true, true, errors);
                    if (distDir != null) {
                        assertTrue(dir.toString(), Objects.deepEquals(dir.toFile().list(), distDir.list()));
                        return FileVisitResult.CONTINUE;
                    } else {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                log.trace("Testing " + path);
                if (path.equals(PROVISIONING_XML)) {
                    getDistFile(path, false, false, errors);
                } else {
                    File distFile = getDistFile(path, true, false, errors);
                    if (distFile != null) {
                        if (path.toFile().length() != distFile.length()) {
                            errors.add(String.format("dist file for %s has an unexpected length: %d not %d",
                                    path, distFile.length(), path.toFile().length()));
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw exc;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        if (installationMetadata.get() == null) {
            errors.add("No .installation directory found");
        }
        if (!errors.isEmpty()) {
            fail(errors.toString());
        }
    }
}
