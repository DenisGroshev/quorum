package app.quorum.telegram.client;

public record TelegramSendResult(TelegramSendOutcome outcome, String errorMessage) {

    public static TelegramSendResult success() {
        return new TelegramSendResult(TelegramSendOutcome.SUCCESS, null);
    }

    public static TelegramSendResult transientFailure(String errorMessage) {
        return new TelegramSendResult(TelegramSendOutcome.TRANSIENT_FAILURE, errorMessage);
    }

    public static TelegramSendResult permanentFailure(String errorMessage) {
        return new TelegramSendResult(TelegramSendOutcome.PERMANENT_FAILURE, errorMessage);
    }
}
