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

import com.tomaszrup.groovyls.util.JavaSourceLocator;

/**
 * Resolves decompiled content and Java navigation URIs across all project scopes.
 * Extracted from {@link GroovyServices} for single-responsibility.
 */
class DecompiledContentResolver {

	private final ProjectScopeManager scopeManager;

	DecompiledContentResolver(ProjectScopeManager scopeManager) {
		this.scopeManager = scopeManager;
	}

	/**
	 * Look up decompiled content across all project scopes.
	 * Returns the content for the first matching scope, or {@code null}
	 * if no scope has decompiled content for the given class.
	 *
	 * @param className fully-qualified class name
	 * @return decompiled source text, or {@code null}
	 */
	String getDecompiledContent(String className) {
		for (ProjectScope scope : scopeManager.getProjectScopes()) {
			JavaSourceLocator locator = scope.getJavaSourceLocator();
			if (locator != null) {
				String content = locator.getDecompiledContent(className);
				if (content != null) {
					return content;
				}
			}
		}
		ProjectScope ds = scopeManager.getDefaultScope();
		if (ds != null && ds.getJavaSourceLocator() != null) {
			return ds.getJavaSourceLocator().getDecompiledContent(className);
		}
		return null;
	}

	/**
	 * Look up decompiled content by URI string across all project scopes.
	 * Supports {@code decompiled:}, {@code jar:}, and {@code jrt:} URIs.
	 *
	 * @param uri the URI string
	 * @return decompiled source text, or {@code null}
	 */
	String getDecompiledContentByURI(String uri) {
		for (ProjectScope scope : scopeManager.getProjectScopes()) {
			JavaSourceLocator locator = scope.getJavaSourceLocator();
			if (locator != null) {
				String content = locator.getDecompiledContentByURI(uri);
				if (content != null) {
					return content;
				}
			}
		}
		ProjectScope ds = scopeManager.getDefaultScope();
		if (ds != null && ds.getJavaSourceLocator() != null) {
			return ds.getJavaSourceLocator().getDecompiledContentByURI(uri);
		}
		return null;
	}

	/**
	 * Resolve a URI for Java definition providers.
	 * Converts virtual dependency URIs to provider-compatible URI forms
	 * (for example, source-jar entries as {@code jar:file:///...!/entry.java}).
	 *
	 * @param uri the current document URI
	 * @return Java-provider-compatible URI string, or {@code null} if unavailable
	 */
	String getJavaNavigationURI(String uri) {
		for (ProjectScope scope : scopeManager.getProjectScopes()) {
			JavaSourceLocator locator = scope.getJavaSourceLocator();
			if (locator != null) {
				String resolvedUri = locator.getJavaNavigationURI(uri);
				if (resolvedUri != null) {
					return resolvedUri;
				}
			}
		}
		ProjectScope ds = scopeManager.getDefaultScope();
		if (ds != null && ds.getJavaSourceLocator() != null) {
			return ds.getJavaSourceLocator().getJavaNavigationURI(uri);
		}
		return null;
	}
}
