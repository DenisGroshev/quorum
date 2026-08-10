package app.quorum.common.exception;

public class SessionForbiddenException extends RuntimeException {
    public SessionForbiddenException(String message) {
        super(message);
    }
}
