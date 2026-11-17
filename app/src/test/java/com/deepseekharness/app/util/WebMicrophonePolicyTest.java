package com.deepseekharness.app.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebMicrophonePolicyTest {
    @Test public void acceptsLocalDshAndHttpsRemoteWithDefaultPorts() {
        assertTrue(WebMicrophonePolicy.allows("http://127.0.0.1:3080/", "http://127.0.0.1:3080/chat", "http://127.0.0.1:3080"));
        assertTrue(WebMicrophonePolicy.allows("http://localhost:3080/", "http://localhost:3080/chat", "http://localhost:3080"));
        assertTrue(WebMicrophonePolicy.allows("http://[::1]:3080/", "http://[::1]:3080/chat", "http://[::1]:3080"));
        assertTrue(WebMicrophonePolicy.allows("https://DSH.example/", "https://dsh.example:443/chat", "https://dsh.example"));
    }

    @Test public void rejectsInsecureRemoteAndLoopbackLookalikes() {
        for (String url : new String[]{"http://192.168.1.2:3080", "http://dsh.example", "http://localhost.evil.example", "http://127.0.0.1.evil.example"}) {
            assertFalse(url, WebMicrophonePolicy.allows(url, url, url));
        }
    }

    @Test public void requiresExactOriginForPageAndRequest() {
        String trusted = "https://dsh.example:3080/";
        for (String other : new String[]{"https://other.example:3080", "https://dsh.example:3081", "http://dsh.example:3080", "https://dsh.example.evil:3080"}) {
            assertFalse(WebMicrophonePolicy.allows(trusted, trusted, other));
            assertFalse(WebMicrophonePolicy.allows(trusted, other, trusted));
        }
        assertFalse(WebMicrophonePolicy.allows("http://127.0.0.1:3080", "http://127.0.0.1:3080", "http://localhost:3080"));
    }

    @Test public void rejectsMissingMalformedAndCredentialBearingUrls() {
        String trusted = "https://dsh.example";
        for (String invalid : new String[]{null, "", "null", "about:blank", "file:///tmp/test.html", "https://user@dsh.example", "https://dsh.example:99999", "https://[invalid"}) {
            assertFalse(WebMicrophonePolicy.allows(invalid, trusted, trusted));
            assertFalse(WebMicrophonePolicy.allows(trusted, invalid, trusted));
            assertFalse(WebMicrophonePolicy.allows(trusted, trusted, invalid));
        }
    }
}
