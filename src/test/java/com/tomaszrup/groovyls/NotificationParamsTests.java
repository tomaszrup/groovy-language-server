package com.tomaszrup.groovyls;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NotificationParamsTests {

    @Test
    void testStatusUpdateParamsConstructorsAndSetters() {
        StatusUpdateParams empty = new StatusUpdateParams();
        empty.setState("importing");
        empty.setMessage("Resolving projects");

        Assertions.assertEquals("importing", empty.getState());
        Assertions.assertEquals("Resolving projects", empty.getMessage());

        StatusUpdateParams full = new StatusUpdateParams("ready", "Done");
        Assertions.assertEquals("ready", full.getState());
        Assertions.assertEquals("Done", full.getMessage());
    }

    @Test
    void testMemoryUsageParamsConstructorsAndSetters() {
        MemoryUsageParams minimal = new MemoryUsageParams(128, 512);
        Assertions.assertEquals(128, minimal.getUsedMB());
        Assertions.assertEquals(512, minimal.getMaxMB());
        Assertions.assertEquals(0, minimal.getActiveScopes());
        Assertions.assertEquals(0, minimal.getEvictedScopes());
        Assertions.assertEquals(0, minimal.getTotalScopes());

        MemoryUsageParams full = new MemoryUsageParams(256, 1024, 3, 1, 8);
        Assertions.assertEquals(256, full.getUsedMB());
        Assertions.assertEquals(1024, full.getMaxMB());
        Assertions.assertEquals(3, full.getActiveScopes());
        Assertions.assertEquals(1, full.getEvictedScopes());
        Assertions.assertEquals(8, full.getTotalScopes());

        full.setUsedMB(300);
        full.setMaxMB(1200);
        full.setActiveScopes(4);
        full.setEvictedScopes(2);
        full.setTotalScopes(9);

        Assertions.assertEquals(300, full.getUsedMB());
        Assertions.assertEquals(1200, full.getMaxMB());
        Assertions.assertEquals(4, full.getActiveScopes());
        Assertions.assertEquals(2, full.getEvictedScopes());
        Assertions.assertEquals(9, full.getTotalScopes());
    }
}
