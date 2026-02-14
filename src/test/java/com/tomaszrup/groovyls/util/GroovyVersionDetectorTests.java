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
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroovyVersionDetectorTests {

    @Test
    void detectFindsCoreGroovyJarVersion() {
        Optional<String> version = GroovyVersionDetector.detect(Arrays.asList(
                "/repo/org/apache/groovy/groovy-json/5.0.4/groovy-json-5.0.4.jar",
                "/repo/org/apache/groovy/groovy/5.0.4/groovy-5.0.4.jar"
        ));

        assertTrue(version.isPresent());
        assertEquals("5.0.4", version.get());
    }

    @Test
    void detectFallsBackToModuleJarsWhenCoreMissing() {
        Optional<String> version = GroovyVersionDetector.detect(Arrays.asList(
                "/repo/org/apache/groovy/groovy-json/4.0.30/groovy-json-4.0.30.jar",
                "/repo/org/apache/groovy/groovy-xml/4.0.30/groovy-xml-4.0.30.jar"
        ));

        assertTrue(version.isPresent());
        assertEquals("4.0.30", version.get());
    }

    @Test
    void detectReturnsEmptyWhenNoGroovyJarsPresent() {
        Optional<String> version = GroovyVersionDetector.detect(Collections.singletonList(
                "/repo/com/google/guava/guava/33.2.1-jre/guava-33.2.1-jre.jar"
        ));

        assertTrue(version.isEmpty());
    }

    @Test
    void detectFindsGroovyVersionFromClassifierStyleArtifact() {
        Optional<String> version = GroovyVersionDetector.detect(Collections.singletonList(
                "/repo/org/spockframework/spock-core/2.4-M1-groovy-4.0/spock-core-2.4-M1-groovy-4.0.jar"
        ));

        assertTrue(version.isPresent());
        assertEquals("4.0", version.get());
    }

    @Test
    void majorParsesValidVersion() {
        Optional<Integer> major = GroovyVersionDetector.major("5.0.4");
        assertTrue(major.isPresent());
        assertEquals(5, major.get().intValue());
    }
}