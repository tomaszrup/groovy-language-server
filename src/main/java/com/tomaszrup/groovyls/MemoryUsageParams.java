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

/**
 * Parameters for the {@code groovy/memoryUsage} custom notification.
 *
 * <p>Sent periodically from the server to the client so the extension
 * can display JVM heap usage in the status bar.</p>
 */
public class MemoryUsageParams {

    /** Used heap memory in megabytes. */
    private int usedMB;

    /** Maximum heap memory in megabytes. */
    private int maxMB;

    public MemoryUsageParams() {
    }

    public MemoryUsageParams(int usedMB, int maxMB) {
        this.usedMB = usedMB;
        this.maxMB = maxMB;
    }

    public int getUsedMB() {
        return usedMB;
    }

    public void setUsedMB(int usedMB) {
        this.usedMB = usedMB;
    }

    public int getMaxMB() {
        return maxMB;
    }

    public void setMaxMB(int maxMB) {
        this.maxMB = maxMB;
    }
}
