package com.deepseekharness.app;

/** Request-local origin metadata. Session IDs identify the source but never enter display text. */
public final class CommandApprovalSource {
    public static final CommandApprovalSource UNKNOWN = new CommandApprovalSource("", "", "", "");
    private final String rootSessionId;
    private final String sessionId;
    private final String conversationTitle;
    private final String agentTitle;

    public CommandApprovalSource(String rootSessionId, String sessionId, String conversationTitle, String agentTitle) {
        this.rootSessionId = rootSessionId == null ? "" : rootSessionId.trim();
        this.sessionId = sessionId == null ? "" : sessionId.trim();
        this.conversationTitle = singleLine(conversationTitle);
        this.agentTitle = singleLine(agentTitle);
    }

    public String getRootSessionId() { return rootSessionId; }
    public String getSessionId() { return sessionId; }

    public String getConversationTitle() {
        if (!conversationTitle.isEmpty()) return conversationTitle;
        return rootSessionId.isEmpty() ? "未知对话" : "未命名对话";
    }

    public boolean isChildAgent() {
        return !rootSessionId.isEmpty() && !sessionId.isEmpty() && !rootSessionId.equals(sessionId);
    }

    public String getAgentTitle() {
        return agentTitle.isEmpty() ? "未命名子代理" : agentTitle;
    }

    private static String singleLine(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        boolean space = false;
        int length = 0;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            // Drop directional overrides; fold controls and line breaks into spaces.
            if (cp == 0x061c || cp == 0x200e || cp == 0x200f
                    || (cp >= 0x202a && cp <= 0x202e) || (cp >= 0x2066 && cp <= 0x2069)) continue;
            if (Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = result.length() > 0;
                continue;
            }
            if (length >= 200) {
                result.append('…');
                break;
            }
            if (space) result.append(' ');
            result.appendCodePoint(cp);
            space = false;
            length++;
        }
        return result.toString();
    }
}
