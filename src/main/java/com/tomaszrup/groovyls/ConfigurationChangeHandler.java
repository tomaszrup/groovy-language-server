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

import com.google.gson.JsonObject;

/**
 * Handles didChangeConfiguration processing, encapsulating the
 * {@link JsonObject} dependency so that {@link GroovyServices} no
 * longer needs it directly.
 */
final class ConfigurationChangeHandler {

    /**
     * Callback interface for forwarding raw configuration settings to the
     * server layer (e.g. so {@code GroovyLanguageServer} can push
     * importer-specific settings like {@code groovy.maven.home}).
     */
    @FunctionalInterface
    public interface SettingsChangeListener {
        void onSettingsChanged(JsonObject settings);
    }

    private final ProjectScopeManager scopeManager;
    private final CompilationService compilationService;
    private SettingsChangeListener settingsChangeListener;

    ConfigurationChangeHandler(ProjectScopeManager scopeManager, CompilationService compilationService) {
        this.scopeManager = scopeManager;
        this.compilationService = compilationService;
    }

    void setSettingsChangeListener(SettingsChangeListener listener) {
        this.settingsChangeListener = listener;
    }

    /**
     * Processes a didChangeConfiguration notification.
     *
     * @param rawSettings the raw settings object from the LSP params
     */
    void handleConfigurationChange(Object rawSettings) {
        if (!(rawSettings instanceof JsonObject)) {
            return;
        }
        JsonObject settings = (JsonObject) rawSettings;
        updateClasspathFromSettings(settings);
        scopeManager.updateFeatureToggles(settings);
        if (settingsChangeListener != null) {
            settingsChangeListener.onSettingsChanged(settings);
        }
    }

    private void updateClasspathFromSettings(JsonObject settings) {
        ProjectScopeManager.ClasspathUpdateResult result = scopeManager.updateClasspathFromSettings(settings);
        if (result != ProjectScopeManager.ClasspathUpdateResult.UPDATED) {
            return;
        }

        ProjectScope ds = scopeManager.getDefaultScope();
        ds.getLock().writeLock().lock();
        try {
            compilationService.recompileForClasspathChange(ds);
        } finally {
            ds.getLock().writeLock().unlock();
        }
    }
}
