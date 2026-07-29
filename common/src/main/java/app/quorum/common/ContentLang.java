package app.quorum.common;

public enum ContentLang {
    RU,
    EN;

    public static ContentLang fromTelegramCode(String code) {
        if ("ru".equalsIgnoreCase(code)) return RU;
        return EN;
    }
}
