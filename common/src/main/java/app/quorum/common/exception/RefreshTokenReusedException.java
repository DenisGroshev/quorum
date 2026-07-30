package app.quorum.common.exception;

public class RefreshTokenReusedException extends RuntimeException {
    public RefreshTokenReusedException(String message) {
        super(message);
    }
}
