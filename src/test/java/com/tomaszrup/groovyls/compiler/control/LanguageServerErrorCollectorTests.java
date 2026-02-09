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
package com.tomaszrup.groovyls.compiler.control;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.messages.SimpleMessage;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LanguageServerErrorCollector}: clear behaviour,
 * null-safety, and no-throw failIfErrors override.
 */
class LanguageServerErrorCollectorTests {

	private CompilerConfiguration config;
	private LanguageServerErrorCollector collector;
	private GroovyLSCompilationUnit cu;

	@BeforeEach
	void setup() {
		config = new CompilerConfiguration();
		cu = new GroovyLSCompilationUnit(config);
		collector = new LanguageServerErrorCollector(config);
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Test
	void testConstruction() {
		Assertions.assertNotNull(collector);
		Assertions.assertFalse(collector.hasErrors());
		Assertions.assertFalse(collector.hasWarnings());
	}

	// ------------------------------------------------------------------
	// clear() — null-safety
	// ------------------------------------------------------------------

	@Test
	void testClearOnFreshCollector() {
		// errors and warnings lists may be null initially
		Assertions.assertDoesNotThrow(() -> collector.clear());
	}

	@Test
	void testClearAfterAddingErrors() {
		collector.addErrorAndContinue(new SimpleMessage("test error", cu));

		Assertions.assertTrue(collector.hasErrors(), "Should have an error before clear");

		collector.clear();

		Assertions.assertFalse(collector.hasErrors(), "Should have no errors after clear");
	}

	@Test
	void testClearAfterAddingWarnings() {
		collector.addWarning(new WarningMessage(WarningMessage.LIKELY_ERRORS,
				"test warning", null, null, null));

		Assertions.assertTrue(collector.hasWarnings(), "Should have a warning before clear");

		collector.clear();

		Assertions.assertFalse(collector.hasWarnings(), "Should have no warnings after clear");
	}

	@Test
	void testClearAfterAddingBothErrorsAndWarnings() {
		collector.addErrorAndContinue(new SimpleMessage("error1", cu));
		collector.addWarning(new WarningMessage(WarningMessage.LIKELY_ERRORS,
				"warning1", null, null, null));

		collector.clear();

		Assertions.assertFalse(collector.hasErrors());
		Assertions.assertFalse(collector.hasWarnings());
	}

	@Test
	void testClearIsIdempotent() {
		collector.addErrorAndContinue(new SimpleMessage("error", cu));
		collector.clear();
		// Second clear should not throw
		Assertions.assertDoesNotThrow(() -> collector.clear());
		Assertions.assertFalse(collector.hasErrors());
	}

	// ------------------------------------------------------------------
	// failIfErrors() — should never throw
	// ------------------------------------------------------------------

	@Test
	void testFailIfErrorsDoesNotThrowWithNoErrors() {
		Assertions.assertDoesNotThrow(() -> collector.failIfErrors());
	}

	@Test
	void testFailIfErrorsDoesNotThrowWithErrors() {
		collector.addErrorAndContinue(new SimpleMessage("failing error", cu));

		Assertions.assertDoesNotThrow(() -> collector.failIfErrors(),
				"LanguageServerErrorCollector.failIfErrors() should never throw");
	}

	@Test
	void testFailIfErrorsDoesNotThrowAfterClear() {
		collector.addErrorAndContinue(new SimpleMessage("some error", cu));
		collector.clear();

		Assertions.assertDoesNotThrow(() -> collector.failIfErrors());
	}

	// ------------------------------------------------------------------
	// Error/warning accumulation still works
	// ------------------------------------------------------------------

	@Test
	void testMultipleErrorsAccumulate() {
		collector.addErrorAndContinue(new SimpleMessage("err1", cu));
		collector.addErrorAndContinue(new SimpleMessage("err2", cu));

		Assertions.assertTrue(collector.hasErrors());
		Assertions.assertEquals(2, collector.getErrorCount());
	}

	@Test
	void testMultipleWarningsAccumulate() {
		collector.addWarning(new WarningMessage(WarningMessage.LIKELY_ERRORS,
				"warn1", null, null, null));
		collector.addWarning(new WarningMessage(WarningMessage.LIKELY_ERRORS,
				"warn2", null, null, null));

		Assertions.assertTrue(collector.hasWarnings());
		Assertions.assertEquals(2, collector.getWarningCount());
	}
}
