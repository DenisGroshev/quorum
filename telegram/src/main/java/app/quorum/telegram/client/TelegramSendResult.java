package app.quorum.telegram.client;

public record TelegramSendResult(TelegramSendOutcome outcome, String errorMessage, Long retryAfterSeconds) {

    public static TelegramSendResult success() {
        return new TelegramSendResult(TelegramSendOutcome.SUCCESS, null, null);
    }

    public static TelegramSendResult transientFailure(String errorMessage) {
        return new TelegramSendResult(TelegramSendOutcome.TRANSIENT_FAILURE, errorMessage, null);
    }

    public static TelegramSendResult transientFailure(String errorMessage, long retryAfterSeconds) {
        return new TelegramSendResult(TelegramSendOutcome.TRANSIENT_FAILURE, errorMessage, retryAfterSeconds);
    }

    public static TelegramSendResult permanentFailure(String errorMessage) {
        return new TelegramSendResult(TelegramSendOutcome.PERMANENT_FAILURE, errorMessage, null);
    }
}
