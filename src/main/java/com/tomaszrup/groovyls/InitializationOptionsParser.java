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
package com.tomaszrup.groovyls;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses the {@code initializationOptions} JSON object sent by the client
 * during the LSP {@code initialize} request.  Extracted from
 * {@link GroovyLanguageServer} to keep each class under 1000 lines.
 */
class InitializationOptionsParser {

    private static final Logger logger = LoggerFactory.getLogger(InitializationOptionsParser.class);

    private static final String PROTOCOL_VERSION_OPTION = "protocolVersion";
    private static final String LOG_LEVEL_OPTION = "logLevel";
    private static final String CLASSPATH_CACHE_OPTION = "classpathCache";
    private static final String ENABLED_IMPORTERS_OPTION = "enabledImporters";
    private static final String BACKFILL_SIBLING_PROJECTS_OPTION = "backfillSiblingProjects";
    private static final String SCOPE_EVICTION_TTL_OPTION = "scopeEvictionTTLSeconds";
    private static final String MEMORY_PRESSURE_THRESHOLD_OPTION = "memoryPressureThreshold";
    private static final String REJECTED_PACKAGES_OPTION = "rejectedPackages";

    /** Immutable container for parsed initialization options. */
    static final class ParsedOptions {
        final boolean classpathCacheDisabled;
        final Set<String> enabledImporters;
        final Boolean backfillSiblingProjects;
        final Long scopeEvictionTTLSeconds;
        final Double memoryPressureThreshold;
        final List<String> rejectedPackages;

        ParsedOptions(boolean classpathCacheDisabled,
                      Set<String> enabledImporters,
                      Boolean backfillSiblingProjects,
                      Long scopeEvictionTTLSeconds,
                      Double memoryPressureThreshold,
                      List<String> rejectedPackages) {
            this.classpathCacheDisabled = classpathCacheDisabled;
            this.enabledImporters = enabledImporters;
            this.rejectedPackages = rejectedPackages;
            this.backfillSiblingProjects = backfillSiblingProjects;
            this.scopeEvictionTTLSeconds = scopeEvictionTTLSeconds;
            this.memoryPressureThreshold = memoryPressureThreshold;
        }
    }

    /**
     * Parse initialization options and apply side-effects that are
     * self-contained (protocol version warning, log level change).
     *
     * @return parsed options, or {@code null} if the input is not a
     *         {@link JsonObject}
     */
    static ParsedOptions parse(Object initOptions) {
        if (!(initOptions instanceof JsonObject)) {
            return null;
        }
        JsonObject opts = (JsonObject) initOptions;
        applyProtocolVersionOption(opts);
        applyLogLevelOption(opts);

        boolean classpathCacheDisabled = parseClasspathCacheOption(opts);
        Set<String> enabledImporters = parseEnabledImportersOption(opts);

        Boolean backfill = null;
        if (opts.has(BACKFILL_SIBLING_PROJECTS_OPTION) && opts.get(BACKFILL_SIBLING_PROJECTS_OPTION).isJsonPrimitive()) {
            backfill = opts.get(BACKFILL_SIBLING_PROJECTS_OPTION).getAsBoolean();
            logger.info("Backfill sibling projects: {}", backfill);
        }

        Long evictionTTL = parseEvictionTTLOption(opts);
        Double memoryThreshold = parseMemoryThresholdOption(opts);
        List<String> rejectedPackages = parseRejectedPackagesOption(opts);

        return new ParsedOptions(classpathCacheDisabled, enabledImporters,
                backfill, evictionTTL, memoryThreshold, rejectedPackages);
    }

    private static void applyProtocolVersionOption(JsonObject opts) {
        if (!opts.has(PROTOCOL_VERSION_OPTION) || !opts.get(PROTOCOL_VERSION_OPTION).isJsonPrimitive()) {
            return;
        }
        String clientProtocolVersion = opts.get(PROTOCOL_VERSION_OPTION).getAsString();
        if (!Protocol.VERSION.equals(clientProtocolVersion)) {
            logger.warn("Protocol version mismatch: extension={}, server={}. "
                            + "Some custom features may not work as expected.",
                    clientProtocolVersion, Protocol.VERSION);
        }
    }

    private static void applyLogLevelOption(JsonObject opts) {
        if (opts.has(LOG_LEVEL_OPTION) && opts.get(LOG_LEVEL_OPTION).isJsonPrimitive()) {
            applyLogLevel(opts.get(LOG_LEVEL_OPTION).getAsString());
        }
    }

    /**
     * Dynamically set the Logback root logger level from a string value.
     * Accepted values (case-insensitive): ERROR, WARN, INFO, DEBUG, TRACE.
     * Invalid values are ignored and a warning is logged.
     */
    static void applyLogLevel(String levelName) {
        try {
            ch.qos.logback.classic.Level level = ch.qos.logback.classic.Level.toLevel(levelName, null);
            if (level == null) {
                logger.warn("Unknown log level '{}', keeping current level", levelName);
                return;
            }
            ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ch.qos.logback.classic.Level previous = root.getLevel();
            root.setLevel(level);
            logger.info("Log level changed from {} to {}", previous, level);
        } catch (Exception e) {
            logger.warn("Failed to set log level to '{}': {}", levelName, e.getMessage());
        }
    }

    private static boolean parseClasspathCacheOption(JsonObject opts) {
        if (opts.has(CLASSPATH_CACHE_OPTION)
                && opts.get(CLASSPATH_CACHE_OPTION).isJsonPrimitive()
                && !opts.get(CLASSPATH_CACHE_OPTION).getAsBoolean()) {
            logger.info("Classpath caching disabled via initializationOptions");
            return true;
        }
        return false;
    }

    private static Set<String> parseEnabledImportersOption(JsonObject opts) {
        if (!opts.has(ENABLED_IMPORTERS_OPTION) || !opts.get(ENABLED_IMPORTERS_OPTION).isJsonArray()) {
            return Collections.emptySet();
        }
        JsonArray arr = opts.getAsJsonArray(ENABLED_IMPORTERS_OPTION);
        Set<String> enabled = new LinkedHashSet<>();
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) {
                enabled.add(el.getAsString());
            }
        }
        if (!enabled.isEmpty()) {
            Set<String> result = Collections.unmodifiableSet(enabled);
            logger.info("Enabled importers: {}", result);
            return result;
        }
        return Collections.emptySet();
    }

    private static Long parseEvictionTTLOption(JsonObject opts) {
        if (opts.has(SCOPE_EVICTION_TTL_OPTION) && opts.get(SCOPE_EVICTION_TTL_OPTION).isJsonPrimitive()) {
            long value = opts.get(SCOPE_EVICTION_TTL_OPTION).getAsLong();
            logger.info("Scope eviction TTL: {}s", value);
            return value;
        }
        return null;
    }

    private static Double parseMemoryThresholdOption(JsonObject opts) {
        if (opts.has(MEMORY_PRESSURE_THRESHOLD_OPTION) && opts.get(MEMORY_PRESSURE_THRESHOLD_OPTION).isJsonPrimitive()) {
            return opts.get(MEMORY_PRESSURE_THRESHOLD_OPTION).getAsDouble();
        }
        return null;
    }

    private static List<String> parseRejectedPackagesOption(JsonObject opts) {
        if (!opts.has(REJECTED_PACKAGES_OPTION) || !opts.get(REJECTED_PACKAGES_OPTION).isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray arr = opts.getAsJsonArray(REJECTED_PACKAGES_OPTION);
        List<String> packages = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) {
                String pkg = el.getAsString().trim();
                if (!pkg.isEmpty()) {
                    packages.add(pkg);
                }
            }
        }
        return Collections.unmodifiableList(packages);
    }

    private InitializationOptionsParser() {
        // utility class
    }
}
