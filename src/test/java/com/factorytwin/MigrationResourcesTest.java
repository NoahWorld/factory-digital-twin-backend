package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/** Renamed resources must not leave obsolete Flyway migrations in the deployable classpath. */
class MigrationResourcesTest {
  static SortedSet<String> migrationFiles(Path directory) throws Exception {
    assertTrue(Files.isDirectory(directory), "Migration directory is missing: " + directory);
    try (var paths = Files.walk(directory)) {
      return paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".sql"))
          .map(directory::relativize).map(Path::toString).collect(Collectors.toCollection(TreeSet::new));
    }
  }

  @Test void classpathMigrationsExactlyMatchCanonicalSourceAndUseUniqueFlywayVersions() throws Exception {
    Path source = Path.of("src/main/resources/db/migration").toAbsolutePath();
    Path compiled = Path.of(TwinDriveDocuments.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("db/migration");
    var expected = migrationFiles(source); var actual = migrationFiles(compiled);
    assertFalse(expected.isEmpty(), "Canonical Flyway migrations must not be empty.");
    assertEquals(expected, actual, "Stale or missing migration resources in the application classpath; rebuild the deployable JAR with mvn clean verify.");
    Map<MigrationVersion, String> versions = new HashMap<>();
    for (String name : actual) {
      String file = Path.of(name).getFileName().toString();
      assertTrue(file.matches("V.+__.+\\.sql"), "Unexpected versioned migration filename: " + file);
      var version = MigrationVersion.fromVersion(file.substring(1, file.indexOf("__")));
      assertNull(versions.putIfAbsent(version, name), "Duplicate Flyway migration version: " + version);
      assertArrayEquals(Files.readAllBytes(source.resolve(name)), Files.readAllBytes(compiled.resolve(name)), "Migration resource content differs from canonical source: " + name);
    }
  }
}
