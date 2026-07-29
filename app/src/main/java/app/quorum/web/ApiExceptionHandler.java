package app.quorum.web;

import app.quorum.common.exception.InvalidDataInitException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidDataInitException.class)
    public ResponseEntity<Map<String, String>> handleInvalidInitData(InvalidDataInitException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", exception.getMessage()));
    }
}
