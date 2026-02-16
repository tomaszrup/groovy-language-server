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

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SpockUtils}: block label detection, lifecycle method
 * detection, allowed successors, and description retrieval.
 */
@SuppressWarnings("all")
class SpockUtilsTests {

	// ------------------------------------------------------------------
	// isSpockBlockLabel
	// ------------------------------------------------------------------

	@Test
	void testIsSpockBlockLabelGiven() {
		Assertions.assertTrue(SpockUtils.isSpockBlockLabel("given"));
	}

	@Test
	void testIsSpockBlockLabelSetup() {
		Assertions.assertTrue(SpockUtils.isSpockBlockLabel("setup"));
	}

	@Test
	void testIsSpockBlockLabelWhen() {
		Assertions.assertTrue(SpockUtils.isSpockBlockLabel("when"));
	}

	@Test
	void testIsSpockBlockLabelThen() {
		Assertions.assertTrue(SpockUtils.isSpockBlockLabel("then"));
	}

	@Test
	void testIsSpockBlockLabelExpect() {
		Assertions.assertTrue(SpockUtils.isSpockBlockLabel("expect"));
	}

	@Test
	void testIsSpockBlockLabelCleanup() {
		Assertions.assertTrue(SpockUtils.isSpockBlockLabel("cleanup"));
	}

	@Test
	void testIsSpockBlockLabelWhere() {
		Assertions.assertTrue(SpockUtils.isSpockBlockLabel("where"));
	}

	@Test
	void testIsSpockBlockLabelAnd() {
		Assertions.assertTrue(SpockUtils.isSpockBlockLabel("and"));
	}

	@Test
	void testIsSpockBlockLabelInvalid() {
		Assertions.assertFalse(SpockUtils.isSpockBlockLabel("notABlock"));
	}

	@Test
	void testIsSpockBlockLabelNull() {
		Assertions.assertFalse(SpockUtils.isSpockBlockLabel(null));
	}

	@Test
	void testIsSpockBlockLabelEmpty() {
		Assertions.assertFalse(SpockUtils.isSpockBlockLabel(""));
	}

	// ------------------------------------------------------------------
	// isSpockLifecycleMethod
	// ------------------------------------------------------------------

	@Test
	void testIsSpockLifecycleMethodSetup() {
		Assertions.assertTrue(SpockUtils.isSpockLifecycleMethod("setup"));
	}

	@Test
	void testIsSpockLifecycleMethodCleanup() {
		Assertions.assertTrue(SpockUtils.isSpockLifecycleMethod("cleanup"));
	}

	@Test
	void testIsSpockLifecycleMethodSetupSpec() {
		Assertions.assertTrue(SpockUtils.isSpockLifecycleMethod("setupSpec"));
	}

	@Test
	void testIsSpockLifecycleMethodCleanupSpec() {
		Assertions.assertTrue(SpockUtils.isSpockLifecycleMethod("cleanupSpec"));
	}

	@Test
	void testIsSpockLifecycleMethodInvalid() {
		Assertions.assertFalse(SpockUtils.isSpockLifecycleMethod("notLifecycle"));
	}

	@Test
	void testIsSpockLifecycleMethodNull() {
		Assertions.assertFalse(SpockUtils.isSpockLifecycleMethod(null));
	}

	// ------------------------------------------------------------------
	// getBlockDescription
	// ------------------------------------------------------------------

	@Test
	void testGetBlockDescriptionGiven() {
		String desc = SpockUtils.getBlockDescription("given");
		Assertions.assertNotNull(desc);
		Assertions.assertTrue(desc.contains("given"));
	}

	@Test
	void testGetBlockDescriptionWhen() {
		String desc = SpockUtils.getBlockDescription("when");
		Assertions.assertNotNull(desc);
		Assertions.assertTrue(desc.contains("when"));
	}

	@Test
	void testGetBlockDescriptionWhere() {
		String desc = SpockUtils.getBlockDescription("where");
		Assertions.assertNotNull(desc);
		Assertions.assertTrue(desc.contains("where"));
	}

	@Test
	void testGetBlockDescriptionInvalid() {
		Assertions.assertNull(SpockUtils.getBlockDescription("notABlock"));
	}

	@Test
	void testGetBlockDescriptionNull() {
		Assertions.assertNull(SpockUtils.getBlockDescription(null));
	}

	// ------------------------------------------------------------------
	// getAllowedSuccessors
	// ------------------------------------------------------------------

	@Test
	void testGetAllowedSuccessorsForGiven() {
		List<String> successors = SpockUtils.getAllowedSuccessors("given");
		Assertions.assertNotNull(successors);
		Assertions.assertTrue(successors.contains("when"));
		Assertions.assertTrue(successors.contains("expect"));
	}

	@Test
	void testGetAllowedSuccessorsForWhen() {
		List<String> successors = SpockUtils.getAllowedSuccessors("when");
		Assertions.assertNotNull(successors);
		Assertions.assertTrue(successors.contains("then"));
	}

	@Test
	void testGetAllowedSuccessorsForWhere() {
		List<String> successors = SpockUtils.getAllowedSuccessors("where");
		Assertions.assertNotNull(successors);
		Assertions.assertTrue(successors.isEmpty(), "where: has no allowed successors");
	}

	@Test
	void testGetAllowedSuccessorsForUnknown() {
		List<String> successors = SpockUtils.getAllowedSuccessors("bogus");
		Assertions.assertNotNull(successors);
		Assertions.assertTrue(successors.isEmpty());
	}

	@Test
	void testGetAllowedSuccessorsForNull() {
		List<String> successors = SpockUtils.getAllowedSuccessors(null);
		Assertions.assertNotNull(successors);
		Assertions.assertTrue(successors.isEmpty());
	}

	// ------------------------------------------------------------------
	// getLifecycleMethodDescription
	// ------------------------------------------------------------------

	@Test
	void testGetLifecycleMethodDescriptionSetup() {
		String desc = SpockUtils.getLifecycleMethodDescription("setup");
		Assertions.assertNotNull(desc);
		Assertions.assertTrue(desc.contains("setup()"));
	}

	@Test
	void testGetLifecycleMethodDescriptionCleanup() {
		String desc = SpockUtils.getLifecycleMethodDescription("cleanup");
		Assertions.assertNotNull(desc);
		Assertions.assertTrue(desc.contains("cleanup()"));
	}

	@Test
	void testGetLifecycleMethodDescriptionSetupSpec() {
		String desc = SpockUtils.getLifecycleMethodDescription("setupSpec");
		Assertions.assertNotNull(desc);
		Assertions.assertTrue(desc.contains("setupSpec()"));
	}

	@Test
	void testGetLifecycleMethodDescriptionCleanupSpec() {
		String desc = SpockUtils.getLifecycleMethodDescription("cleanupSpec");
		Assertions.assertNotNull(desc);
		Assertions.assertTrue(desc.contains("cleanupSpec()"));
	}

	@Test
	void testGetLifecycleMethodDescriptionInvalid() {
		Assertions.assertNull(SpockUtils.getLifecycleMethodDescription("notLifecycle"));
	}

	@Test
	void testGetLifecycleMethodDescriptionNull() {
		Assertions.assertNull(SpockUtils.getLifecycleMethodDescription(null));
	}

	// ------------------------------------------------------------------
	// isSpockSpecification — null
	// ------------------------------------------------------------------

	@Test
	void testIsSpockSpecificationNull() {
		Assertions.assertFalse(SpockUtils.isSpockSpecification(null));
	}

	// ------------------------------------------------------------------
	// isSpockFeatureMethod — null
	// ------------------------------------------------------------------

	@Test
	void testIsSpockFeatureMethodNull() {
		Assertions.assertFalse(SpockUtils.isSpockFeatureMethod(null));
	}

	// ------------------------------------------------------------------
	// getFeatureMethodDescription — null
	// ------------------------------------------------------------------

	@Test
	void testGetFeatureMethodDescriptionNull() {
		Assertions.assertNull(SpockUtils.getFeatureMethodDescription(null));
	}

	// ------------------------------------------------------------------
	// getSpecificationDescription — null
	// ------------------------------------------------------------------

	@Test
	void testGetSpecificationDescriptionNull() {
		Assertions.assertNull(SpockUtils.getSpecificationDescription(null));
	}

	// ------------------------------------------------------------------
	// Constants
	// ------------------------------------------------------------------

	@Test
	void testSpockBlockLabelsContainsAllExpected() {
		Assertions.assertEquals(8, SpockUtils.SPOCK_BLOCK_LABELS.size());
	}

	@Test
	void testSpockLifecycleMethodsContainsAllExpected() {
		Assertions.assertEquals(4, SpockUtils.SPOCK_LIFECYCLE_METHODS.size());
	}

	@Test
	void testSpockAnnotationsNotEmpty() {
		Assertions.assertFalse(SpockUtils.SPOCK_ANNOTATIONS.isEmpty());
		Assertions.assertTrue(SpockUtils.SPOCK_ANNOTATIONS.contains("spock.lang.Unroll"));
	}

	@Test
	void testBlockDescriptionsHaveAllLabels() {
		// All block labels except "and" reuse descriptions; check we have entries
		for (String label : SpockUtils.SPOCK_BLOCK_LABELS) {
			Assertions.assertNotNull(SpockUtils.BLOCK_DESCRIPTIONS.get(label),
					"Missing BLOCK_DESCRIPTIONS entry for: " + label);
		}
	}

	@Test
	void testAllowedSuccessorsHaveAllLabels() {
		for (String label : SpockUtils.SPOCK_BLOCK_LABELS) {
			Assertions.assertTrue(SpockUtils.ALLOWED_SUCCESSORS.containsKey(label),
					"Missing ALLOWED_SUCCESSORS entry for: " + label);
		}
	}
}
