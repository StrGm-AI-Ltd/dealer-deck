package ai.strgm.dealer;

import ai.strgm.dealer.api.model.ErrorBody;
import ai.strgm.dealer.api.model.ErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handle(final ApiException e) {
        final var b = ResponseEntity.status(e.status).contentType(MediaType.APPLICATION_JSON);
        return e.body != null ? b.body(e.body) : b.body(new ErrorResponse(new ErrorBody(e.getMessage(), e.type)));
    }
}
