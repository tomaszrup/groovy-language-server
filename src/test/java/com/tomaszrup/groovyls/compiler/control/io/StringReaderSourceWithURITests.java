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
package com.tomaszrup.groovyls.compiler.control.io;

import java.net.URI;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StringReaderSourceWithURI}: construction and URI
 * retrieval.
 */
class StringReaderSourceWithURITests {

	@Test
	void testConstructorAndGetURI() {
		URI uri = URI.create("file:///workspace/src/Hello.groovy");
		CompilerConfiguration config = new CompilerConfiguration();
		String source = "class Hello {}";

		StringReaderSourceWithURI source1 = new StringReaderSourceWithURI(source, uri, config);

		Assertions.assertEquals(uri, source1.getURI());
	}

	@Test
	void testDifferentURIs() {
		CompilerConfiguration config = new CompilerConfiguration();
		URI uri1 = URI.create("file:///a/A.groovy");
		URI uri2 = URI.create("file:///b/B.groovy");

		StringReaderSourceWithURI s1 = new StringReaderSourceWithURI("class A {}", uri1, config);
		StringReaderSourceWithURI s2 = new StringReaderSourceWithURI("class B {}", uri2, config);

		Assertions.assertEquals(uri1, s1.getURI());
		Assertions.assertEquals(uri2, s2.getURI());
		Assertions.assertNotEquals(s1.getURI(), s2.getURI());
	}

	@Test
	void testNullURI() {
		CompilerConfiguration config = new CompilerConfiguration();
		StringReaderSourceWithURI source = new StringReaderSourceWithURI("class X {}", null, config);

		Assertions.assertNull(source.getURI());
	}

	@Test
	void testCanReadSource() throws Exception {
		URI uri = URI.create("file:///workspace/src/Readable.groovy");
		CompilerConfiguration config = new CompilerConfiguration();
		String sourceText = "class Readable { String name }";

		StringReaderSourceWithURI source = new StringReaderSourceWithURI(sourceText, uri, config);

		// The parent class StringReaderSource should allow reading
		Assertions.assertNotNull(source.getReader());
	}
}
