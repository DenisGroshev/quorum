package app.quorum.common.exception;

public class InvalidDataInitException extends RuntimeException {
    public InvalidDataInitException(String message) {
        super(message);
    }

    public InvalidDataInitException(String message, Throwable cause) {
        super(message, cause);
    }
}
