package com.deepseekharness.app.util;

import java.net.URI;
import java.net.URISyntaxException;

/** Origin checks for microphone requests; HTTP is usable only on loopback. */
public final class WebMicrophonePolicy {
    private WebMicrophonePolicy() {}

    public static boolean allows(String trustedUrl, String pageUrl, String requestOrigin) {
        URI trusted = parse(trustedUrl);
        URI page = parse(pageUrl);
        URI origin = parse(requestOrigin);
        return trusted != null && page != null && origin != null
                && secure(trusted) && sameOrigin(trusted, page) && sameOrigin(trusted, origin);
    }

    private static URI parse(String value) {
        if (value == null) return null;
        try {
            URI uri = new URI(value);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() > 65535) return null;
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean secure(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) return true;
        if (!"http".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "[::1]".equals(host);
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost()) && port(first) == port(second);
    }

    private static int port(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }
}
