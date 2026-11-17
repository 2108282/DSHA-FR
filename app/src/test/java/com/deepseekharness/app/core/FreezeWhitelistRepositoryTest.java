package com.deepseekharness.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import org.junit.Test;

/** 纯 Java 规范化契约；留待云端运行，本机不执行编译。 */
public class FreezeWhitelistRepositoryTest {
    private static String repeated(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void reject(String value) {
        try {
            FreezeWhitelistRepository.normalize(value);
            fail("应拒绝无效规则");
        } catch (IllegalArgumentException expected) {}
    }

    @Test public void normalizesOnlyLineEdgesAndLineEndings() {
        assertEquals("# comment\nname: worker one\nscript:/some path/worker.py\n",
                FreezeWhitelistRepository.normalize(" \t# comment\r\n\tname: worker one \t\r\nscript:/some path/worker.py\r"));
        assertEquals("", FreezeWhitelistRepository.normalize(" \t\r\n"));
    }

    @Test public void literalArgumentsAndSpacesAreKept() {
        String value = "name:a (b)\nscript:/tmp/x 'quote' $(literal) `literal`;*.py\n";
        assertEquals(value, FreezeWhitelistRepository.normalize(value));
        reject("script: /tmp/a.py");
    }

    @Test public void rejectsInvalidNamesAndPrefixes() {
        reject("name:");
        reject("name:/bin/sh");
        reject("script:relative.py");
        reject("unknown:worker");
        reject("name:a\tb");
    }

    @Test public void rejectsControlsEvenInComments() {
        reject("# nul\0");
        reject("name:a\u001b");
        reject("name:a\u0085");
        reject("name:a\u2028");
        reject("name:a\u2029");
        reject("name:a\ud800");
    }

    @Test public void countsUtf8BytesForNameAndRuleLimits() {
        assertEquals("name:" + repeated("a", 80) + "\n", FreezeWhitelistRepository.normalize("name:" + repeated("a", 80)));
        reject("name:" + repeated("a", 81));
        reject("name:" + repeated("中", 27));
        String limit = "script:/" + repeated("a", 504);
        assertEquals(limit + "\n", FreezeWhitelistRepository.normalize(limit));
        reject(limit + "a");
    }

    @Test public void enforcesRuleCountAndWholeTextLimit() {
        assertEquals(repeated("name:a\n", 128), FreezeWhitelistRepository.normalize(repeated("name:a\n", 128)));
        reject(repeated("name:a\n", 129));
        reject("#" + repeated("a", 16384));
        // Adding the canonical newline must not produce a file above 16KiB.
        reject("#" + repeated("a", 16383));
        assertEquals("#" + repeated("a", 16382) + "\n", FreezeWhitelistRepository.normalize("#" + repeated("a", 16382)));
    }
}
