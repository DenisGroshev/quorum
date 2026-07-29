package app.quorum.notification.domain;

public enum OutboxStatus {
    PENDING,
    SENT,
    FAILED,
    SKIPPED
}
