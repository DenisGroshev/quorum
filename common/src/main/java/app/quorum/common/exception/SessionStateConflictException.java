package app.quorum.common.exception;

public class SessionStateConflictException extends RuntimeException {
    public SessionStateConflictException(String message) {
        super(message);
    }
}
