package com.deepseekharness.app.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginSourceTest {
    @Test public void githubSpecsAreResolvedByBackendAgainstPreviewCommit() {
        PluginSource head = PluginSource.parse("https://github.com/2108282/dsh-smooth-stream");
        assertEquals("", head.officialSpec());
        assertTrue(head.command().contains("2108282"));
        PluginSource tree = PluginSource.parse("https://github.com/2108282/dsh-smooth-stream/tree/main/packages/plugin");
        assertEquals("", tree.officialSpec());
        assertTrue(tree.command().contains("packages/plugin"));
    }

    @Test public void npmUsesOfficialPackageSpecAndArchivesUseFallback() {
        assertEquals("dsh-smooth-stream@0.10.4",
                PluginSource.parse("npm:dsh-smooth-stream@0.10.4").officialSpec());
        assertEquals("", PluginSource.parse("https://example.com/plugin.tgz").officialSpec());
        assertTrue(PluginSource.parse("https://github.com/2108282/dsh-smooth-stream/releases/latest")
                .officialSpec().isEmpty());
    }

    @Test public void rejectsInvalidInputsAndAmbiguousLinks() {
        for (String invalid : new String[]{"npm:--help", "npm:../pkg", "npm:a;id", "npm:a$(id)",
                "npm:a b", "https://example.com/o/r", "git@evil.com:o/r",
                "https://github.com.evil.test/o/r", "https://evil@github.com/o/r",
                "https://github.com/o/r/issues/1", "https://github.com/o/r/tree/main/%2e%2e/root",
                "https://github.com/o/r https://github.com/o/other", "file:///plugin.zip",
                "https://github.com/o/r/blob/main", "https://github.com:443/o/r"}) {
            try { PluginSource.parse(invalid); fail(invalid); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void archivesAndReleaseAssetsNeverBecomeFloatingGithubSpecs() {
        for (String link : new String[]{
                "https://github.com/o/r/archive/refs/tags/v2.tar.gz",
                "https://github.com/o/r/releases/download/v2/plugin.zip",
                "https://github.com/o/r/releases/latest/download/plugin.tgz",
                "https://codeload.github.com/o/r/tar.gz/refs/heads/dev",
                "https://plugins.example.com/plugin.tgz?download=1"}) {
            PluginSource source = PluginSource.parse(link);
            assertTrue(source.command().startsWith("download ") || source.command().startsWith("release "));
            assertEquals("", source.officialSpec());
        }
    }

    @Test public void recognizesGithubShareAndEncodedSlashBranch() {
        assertEquals("o", PluginSource.parse("分享插件：[点这里](https://github.com/o/r)，试一下").github.owner);
        PluginSource branch = PluginSource.parse("https://github.com/o/r/tree/feature%2Fandroid/packages/plugin");
        assertEquals("feature/android/packages/plugin", branch.github.treePath);
        assertTrue(branch.command().contains("feature/android/packages/plugin"));
        assertEquals("dev/packages/plugin", PluginSource.parse(
                "https://github.com/o/r/blob/dev/packages/plugin/package.json").github.treePath);
        assertEquals("r", PluginSource.parse("git@github.com:o/r.git").github.repo);
        assertEquals("r", PluginSource.parse("o/r").github.repo);
    }
}
