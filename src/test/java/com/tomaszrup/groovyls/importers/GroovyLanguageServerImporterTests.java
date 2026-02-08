////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Tomasz Rup
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
//
// Author: Tomasz Rup
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls.importers;

import com.tomaszrup.groovyls.GroovyLanguageServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for the ProjectImporter integration in GroovyLanguageServer,
 * verifying the importer registry and configuration hooks.
 */
class GroovyLanguageServerImporterTests {

    @Test
    void testServerRegistersGradleAndMavenImporters() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        List<ProjectImporter> importers = server.getImporters();

        Assertions.assertNotNull(importers);
        Assertions.assertEquals(2, importers.size());
    }

    @Test
    void testGradleImporterIsRegisteredFirst() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        List<ProjectImporter> importers = server.getImporters();

        // Gradle should be first (higher priority — wins when both build files exist)
        Assertions.assertInstanceOf(GradleProjectImporter.class, importers.get(0));
    }

    @Test
    void testMavenImporterIsRegisteredSecond() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        List<ProjectImporter> importers = server.getImporters();

        Assertions.assertInstanceOf(MavenProjectImporter.class, importers.get(1));
    }

    @Test
    void testImporterNamesAreCorrect() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        List<ProjectImporter> importers = server.getImporters();

        Assertions.assertEquals("Gradle", importers.get(0).getName());
        Assertions.assertEquals("Maven", importers.get(1).getName());
    }

    @Test
    void testGradleImporterDoesNotRecognizePomXml() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ProjectImporter gradle = server.getImporters().get(0);

        Assertions.assertFalse(gradle.isProjectFile("pom.xml"));
        Assertions.assertTrue(gradle.isProjectFile("build.gradle"));
        Assertions.assertTrue(gradle.isProjectFile("build.gradle.kts"));
    }

    @Test
    void testMavenImporterDoesNotRecognizeBuildGradle() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ProjectImporter maven = server.getImporters().get(1);

        Assertions.assertTrue(maven.isProjectFile("pom.xml"));
        Assertions.assertFalse(maven.isProjectFile("build.gradle"));
        Assertions.assertFalse(maven.isProjectFile("build.gradle.kts"));
    }

    @Test
    void testImportersAreDistinctInstances() {
        GroovyLanguageServer server1 = new GroovyLanguageServer();
        GroovyLanguageServer server2 = new GroovyLanguageServer();

        // Each server should have its own importer instances
        Assertions.assertNotSame(server1.getImporters(), server2.getImporters());
        Assertions.assertNotSame(server1.getImporters().get(0), server2.getImporters().get(0));
    }
}
