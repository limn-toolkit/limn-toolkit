package limn.icons.tabler;

/**
 * Tabler's <b>Development</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerDevelopment implements TablerIcon {

    A_B("a-b"),
    AI("ai"),
    AI_AGENT("ai-agent"),
    AI_AGENTS("ai-agents"),
    AI_GATEWAY("ai-gateway"),
    ALT("alt"),
    API("api"),
    API_APP("api-app"),
    API_APP_OFF("api-app-off"),
    API_BOOK("api-book"),
    API_OFF("api-off"),
    APPS("apps"),
    APPS_OFF("apps-off"),
    ASSEMBLY("assembly"),
    ASSEMBLY_OFF("assembly-off"),
    ASSET("asset"),
    AUTH_2FA("auth-2fa"),
    BOT_ID("bot-id"),
    BUG("bug"),
    BUG_OFF("bug-off"),
    CANARY("canary"),
    CODE_AI("code-ai"),
    CODEBLOCK("codeblock"),
    CONNECTION("connection"),
    FIREWALL_CHECK("firewall-check"),
    FIREWALL_FLAME("firewall-flame"),
    IMAGE_GENERATION("image-generation"),
    LOGS("logs"),
    MICROFRONTENDS("microfrontends"),
    MIDDLEWARE("middleware"),
    PACKAGE_EXPORT("package-export"),
    PACKAGE_IMPORT("package-import"),
    PACKAGES("packages"),
    PIPELINE("pipeline"),
    PLAY_BUG("play-bug"),
    PLAY_BUGS("play-bugs"),
    PROMPT("prompt"),
    QUEUE_POP_IN("queue-pop-in"),
    QUEUE_POP_OUT("queue-pop-out"),
    SCAN_TRACES("scan-traces"),
    SDK("sdk"),
    SEO("seo"),
    SERVERLESS("serverless"),
    SITEMAP("sitemap"),
    SITEMAP_OFF("sitemap-off"),
    SOURCE_CODE("source-code"),
    TERMINAL_2("terminal-2"),
    THINKING_HIGH("thinking-high"),
    THINKING_LOW("thinking-low"),
    THINKING_MEDIUM("thinking-medium"),
    TIMELINE_EVENT("timeline-event"),
    TIMELINE_EVENT_EXCLAMATION("timeline-event-exclamation"),
    TIMELINE_EVENT_MINUS("timeline-event-minus"),
    TIMELINE_EVENT_PLUS("timeline-event-plus"),
    TIMELINE_EVENT_TEXT("timeline-event-text"),
    TIMELINE_EVENT_X("timeline-event-x"),
    USER_KEY("user-key"),
    VERSIONS("versions"),
    VERSIONS_OFF("versions-off"),
    WEBHOOK("webhook"),
    WEBHOOK_OFF("webhook-off"),
    ZERO_CONFIG("zero-config");

    private final String iconName;

    TablerDevelopment(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
