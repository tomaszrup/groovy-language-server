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

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Reusable no-op {@link LanguageClient} for tests. All methods are empty or
 * return {@code null} by default. An optional callback can be supplied to
 * intercept {@link #publishDiagnostics} calls — useful for tests that need
 * to inspect published diagnostics.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // No-op client
 * services.connect(new TestLanguageClient());
 *
 * // Client that captures diagnostics
 * List<PublishDiagnosticsParams> diags = new ArrayList<>();
 * services.connect(new TestLanguageClient(diags::add));
 * }</pre>
 */
public class TestLanguageClient implements LanguageClient {

    private final Consumer<PublishDiagnosticsParams> diagnosticsConsumer;

    /**
     * Creates a no-op test client.
     */
    public TestLanguageClient() {
        this(null);
    }

    /**
     * Creates a test client that delegates {@link #publishDiagnostics} calls
     * to the given consumer.
     *
     * @param diagnosticsConsumer callback invoked on each
     *                            {@code publishDiagnostics} notification,
     *                            or {@code null} for no-op
     */
    public TestLanguageClient(Consumer<PublishDiagnosticsParams> diagnosticsConsumer) {
        this.diagnosticsConsumer = diagnosticsConsumer;
    }

    @Override
    public void telemetryEvent(Object object) {
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return null;
    }

    @Override
    public void showMessage(MessageParams messageParams) {
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        if (diagnosticsConsumer != null) {
            diagnosticsConsumer.accept(diagnostics);
        }
    }

    @Override
    public void logMessage(MessageParams message) {
    }
}
