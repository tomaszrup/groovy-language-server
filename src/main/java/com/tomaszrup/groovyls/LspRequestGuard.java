////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
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
// Author: Tomasz Rup (originally Prominic.NET, Inc.)
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides fail-soft request execution and error handling
 * for LSP request methods.
 * Extracted from {@link GroovyServices} for single-responsibility.
 */
class LspRequestGuard {
	private static final Logger logger = LoggerFactory.getLogger(LspRequestGuard.class);

	private final ProjectScopeManager scopeManager;

	LspRequestGuard(ProjectScopeManager scopeManager) {
		this.scopeManager = scopeManager;
	}

	<T> CompletableFuture<T> failSoftRequest(String requestName, URI uri,
			Supplier<CompletableFuture<T>> requestCall, T fallbackValue) {
		try {
			CompletableFuture<T> future = requestCall.get();
			if (future == null) {
				return CompletableFuture.completedFuture(fallbackValue);
			}
			return future.exceptionally(throwable -> {
				Throwable root = unwrapRequestThrowable(throwable);
				if (isFatalRequestThrowable(root)) {
					throwAsUnchecked(root);
				}
				logRequestFailure(requestName, uri, root, true);
				return fallbackValue;
			});
		} catch (Exception | LinkageError throwable) {
			Throwable root = unwrapRequestThrowable(throwable);
			if (isFatalRequestThrowable(root)) {
				throwAsUnchecked(root);
			}
			logRequestFailure(requestName, uri, root, false);
			return CompletableFuture.completedFuture(fallbackValue);
		}
	}

	void logRequestFailure(String requestName, URI uri, Throwable throwable,
			boolean fromAsyncStage) {
		ProjectScope scope = uri != null ? scopeManager.findProjectScope(uri) : null;
		Path projectRoot = scope != null ? scope.getProjectRoot() : null;
		String phase = fromAsyncStage ? "async" : "sync";
		if (logger.isWarnEnabled()) {
			logger.warn("{} request failed ({}), uri={}, projectRoot={}, error={}", requestName, phase,
					uri, projectRoot, summarizeThrowable(throwable));
		}
		logger.debug("{} request failure details", requestName, throwable);
	}

	static String summarizeThrowable(Throwable throwable) {
		if (throwable == null) {
			return "<null>";
		}
		String message = throwable.getMessage();
		if (message == null || message.isBlank()) {
			return throwable.getClass().getName();
		}
		return throwable.getClass().getName() + ": " + message;
	}

	static Throwable unwrapRequestThrowable(Throwable throwable) {
		Throwable current = throwable;
		while (current instanceof java.util.concurrent.CompletionException
				|| current instanceof ExecutionException) {
			Throwable cause = current.getCause();
			if (cause == null) {
				break;
			}
			current = cause;
		}
		return current;
	}

	static boolean isFatalRequestThrowable(Throwable throwable) {
		return throwable instanceof VirtualMachineError;
	}

	static void throwAsUnchecked(Throwable throwable) {
		if (throwable instanceof RuntimeException) {
			throw (RuntimeException) throwable;
		}
		if (throwable instanceof Error) {
			throw (Error) throwable;
		}
		throw new IllegalStateException("Unexpected checked throwable", throwable);
	}
}
