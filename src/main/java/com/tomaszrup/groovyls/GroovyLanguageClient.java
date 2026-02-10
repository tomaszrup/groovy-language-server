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

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Extended language client interface with Groovy-specific custom notifications.
 *
 * <p>Adds the {@code groovy/statusUpdate} notification that provides structured
 * status transitions to the client, replacing the previous approach of parsing
 * {@code window/logMessage} text with fragile string-prefix matching.</p>
 */
public interface GroovyLanguageClient extends LanguageClient {

    /**
     * Notify the client of a server status change.
     *
     * @param params the status update parameters containing {@code state}
     *               ({@code "importing"}, {@code "ready"}, {@code "error"})
     *               and an optional {@code message} detail
     */
    @JsonNotification("groovy/statusUpdate")
    void statusUpdate(StatusUpdateParams params);

    /**
     * Periodically report the server's JVM memory usage to the client.
     *
     * @param params a JSON object with {@code usedMB} and {@code maxMB} fields
     */
    @JsonNotification("groovy/memoryUsage")
    void memoryUsage(MemoryUsageParams params);
}
