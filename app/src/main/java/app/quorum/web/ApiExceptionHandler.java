package app.quorum.web;

import app.quorum.common.exception.DomainRuleException;
import app.quorum.common.exception.InvalidAccessTokenException;
import app.quorum.common.exception.InvalidDataInitException;
import app.quorum.common.exception.InvalidRefreshTokenException;
import app.quorum.common.exception.MovieNotFoundException;
import app.quorum.common.exception.RefreshTokenReusedException;
import app.quorum.common.exception.SessionForbiddenException;
import app.quorum.common.exception.SessionNotFoundException;
import app.quorum.common.exception.SessionStateConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidDataInitException.class)
    public ProblemDetail handleInvalidInitData(InvalidDataInitException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
        problem.setTitle("Invalid Telegram initData");
        return problem;
    }

    @ExceptionHandler(InvalidAccessTokenException.class)
    public ProblemDetail handleInvalidAccessToken(InvalidAccessTokenException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
        problem.setTitle("Invalid access token");
        return problem;
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
        problem.setTitle("Invalid refresh token");
        return problem;
    }

    @ExceptionHandler(RefreshTokenReusedException.class)
    public ProblemDetail handleRefreshTokenReused(RefreshTokenReusedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Refresh token has already been used and was revoked");
        problem.setTitle("Refresh token reused");
        problem.setProperty("code", exception.getMessage());
        return problem;
    }

    @ExceptionHandler(DomainRuleException.class)
    public ProblemDetail handleDomainRule(DomainRuleException exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
        problem.setTitle("Domain rule violated");
        problem.setProperty("code", exception.getMessage());
        return problem;
    }

    @ExceptionHandler(MovieNotFoundException.class)
    public ProblemDetail handleMovieNotFound(MovieNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Movie not found");
        return problem;
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ProblemDetail handleSessionNotFound(SessionNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Session not found");
        return problem;
    }

    @ExceptionHandler(SessionForbiddenException.class)
    public ProblemDetail handleSessionForbidden(SessionForbiddenException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
        problem.setTitle("Forbidden");
        problem.setProperty("code", exception.getMessage());
        return problem;
    }

    @ExceptionHandler(SessionStateConflictException.class)
    public ProblemDetail handleSessionStateConflict(SessionStateConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Session state conflict");
        problem.setProperty("code", exception.getMessage());
        return problem;
    }
}
