package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PluginCommandPolicyTest {
    @Test public void listAndPreviewUseShortReadBudget() {
        assertEquals(30_000L, PluginCommandPolicy.timeoutMs("list"));
        assertEquals(30_000L, PluginCommandPolicy.timeoutMs("  list  "));
        assertEquals(30_000L, PluginCommandPolicy.timeoutMs("show-preview 'abc'"));
    }

    @Test public void mutationsAllowInstallRecoveryAndCleanupToFinish() {
        String[] commands = {"install-preview", "rollback", "update", "reconcile", "import",
                "download", "npm", "github", "release"};
        for (String command : commands) {
            long timeout = PluginCommandPolicy.timeoutMs(command + " 'plugin argument'");
            assertEquals(command, 1_500_000L, timeout);
            assertTrue(command, timeout > 2 * (600_000L + 15_000L));
        }
    }

    @Test public void otherCommandsKeepExistingBudget() {
        for (String command : new String[]{"inspect '{}'", "check-updates", "delete demo",
                "export '[]' '/tmp/archive'", "safe-mode status", "discard-preview abc"}) {
            assertEquals(command, 600_000L, PluginCommandPolicy.timeoutMs(command));
        }
    }

    @Test public void parsesCommandOnlyAndHandlesMissingInput() {
        assertEquals(600_000L, PluginCommandPolicy.timeoutMs(null));
        assertEquals(600_000L, PluginCommandPolicy.timeoutMs(""));
        assertEquals(600_000L, PluginCommandPolicy.timeoutMs("inspect 'list'"));
        assertEquals(1_500_000L, PluginCommandPolicy.timeoutMs("rollback\tdemo"));
    }
}
