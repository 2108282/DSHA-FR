#!/usr/bin/env python3
"""Run guard settings and approval gate Java tests without Gradle/Android resources.

Requires Python 3, JDK 17+ (javac/java), and a POSIX sh. Compiles the actual
production classes and existing @Test methods. Minimal Android/ConfigStore and
JUnit stubs supply only their host-side dependencies; no APK or device is used.
All build output and Java fixtures stay in a temporary directory. Production
Root commands are exercised only after the tests replace paths with fixtures.
"""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import textwrap


REPO = Path(__file__).resolve().parent.parent
STUBS = {
    "android/content/Context.java": """
        package android.content;
        public abstract class Context {
            public abstract Context getApplicationContext();
            public abstract java.io.File getFilesDir();
        }
    """,
    "android/util/Log.java": """
        package android.util;
        public class Log {
            public static int w(String tag, String message) { return 0; }
            public static int w(String tag, String message, Throwable error) { return 0; }
        }
    """,
    "com/deepseekharness/app/core/ConfigStore.java": """
        package com.deepseekharness.app.core;
        public class ConfigStore {
            private volatile boolean enabled = true;
            public boolean isConfirmShell() { return enabled; }
            public void setConfirmShell(boolean enabled) { this.enabled = enabled; }
        }
    """,
    "org/junit/Test.java": """
        package org.junit;
        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
        @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
        public @interface Test {}
    """,
    "org/junit/Assert.java": """
        package org.junit;
        public class Assert {
            public static void assertTrue(boolean value) {
                if (!value) throw new AssertionError("expected true");
            }
            public static void assertFalse(boolean value) {
                if (value) throw new AssertionError("expected false");
            }
            public static void assertEquals(Object expected, Object actual) {
                if (!java.util.Objects.equals(expected, actual)) {
                    throw new AssertionError("expected " + expected + ", actual " + actual);
                }
            }
            public static void assertEquals(long expected, long actual) {
                if (expected != actual) {
                    throw new AssertionError("expected " + expected + ", actual " + actual);
                }
            }
            public static void assertNotEquals(long unexpected, long actual) {
                if (unexpected == actual) throw new AssertionError("unexpected " + actual);
            }
        }
    """,
    "Runner.java": """
        public class Runner {
            public static void main(String[] classes) throws Exception {
                int passed = 0;
                for (String name : classes) {
                    Class<?> type = Class.forName(name);
                    java.lang.reflect.Method[] methods = type.getDeclaredMethods();
                    java.util.Arrays.sort(methods,
                            java.util.Comparator.comparing(java.lang.reflect.Method::getName));
                    int found = 0;
                    for (java.lang.reflect.Method method : methods) {
                        if (!method.isAnnotationPresent(org.junit.Test.class)) continue;
                        found++;
                        try {
                            // JUnit 4 creates a new test instance for every method.
                            method.invoke(type.getConstructor().newInstance());
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw new AssertionError(name + "." + method.getName(), failure.getCause());
                        }
                        System.out.println("PASS " + type.getSimpleName() + "." + method.getName());
                        passed++;
                    }
                    if (found == 0) throw new AssertionError("No @Test methods in " + name);
                }
                System.out.println("PASS " + passed + " isolated Java tests");
            }
        }
    """,
}
PRODUCTION = [
    "app/src/main/java/com/deepseekharness/app/core/GuardSettings.java",
    "app/src/main/java/com/deepseekharness/app/ApprovalRequestGate.java",
]
TESTS = {
    "com.deepseekharness.app.core.GuardSettingsTest":
        "app/src/test/java/com/deepseekharness/app/core/GuardSettingsTest.java",
    "com.deepseekharness.app.ApprovalRequestGateTest":
        "app/src/test/java/com/deepseekharness/app/ApprovalRequestGateTest.java",
}


def main():
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise SystemExit("JDK 17+ required: javac and java must be on PATH")

    with tempfile.TemporaryDirectory(prefix="dsha-guard-tests-") as temporary:
        root = Path(temporary).resolve()
        sources = []
        for name, content in STUBS.items():
            path = root / "stubs" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(textwrap.dedent(content).strip() + "\n", encoding="utf-8")
            sources.append(str(path))
        sources.extend(str(REPO / path) for path in PRODUCTION + list(TESTS.values()))
        classes = root / "classes"
        classes.mkdir()
        fixtures = root / "fixtures"
        fixtures.mkdir()
        # An accidental call to the production su entry point must not reach the device.
        binaries = root / "bin"
        binaries.mkdir()
        su = binaries / "su"
        su.write_text("#!/bin/sh\necho 'ERROR: su forbidden in host tests' >&2\nexit 99\n",
                      encoding="utf-8")
        su.chmod(0o755)
        env = os.environ.copy()
        env["PATH"] = str(binaries) + os.pathsep + env.get("PATH", "")
        subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-d", str(classes)]
                       + sources, cwd=root, env=env, check=True, timeout=45)
        subprocess.run([java, "-Djava.io.tmpdir=" + str(fixtures), "-cp", str(classes),
                        "Runner"] + list(TESTS), cwd=root, env=env, check=True, timeout=45)


if __name__ == "__main__":
    main()
