package ai.strgm.deck;

import ai.strgm.deck.api.model.ErrorBody;
import ai.strgm.deck.api.model.ErrorResponse;
import ai.strgm.deck.api.model.LeaseConflict;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class Errors {

    public static ResponseEntity<Object> of(final HttpStatus status, final String type, final String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(new ErrorBody(message, type)));
    }

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<String> handle(final UpstreamException e) {
        final var b = ResponseEntity.status(e.status).contentType(MediaType.APPLICATION_JSON);
        if (e.retryAfter != null) {
            b.header("Retry-After", e.retryAfter);
        }
        return b.body(e.body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handle(final ApiException e) {
        if (e.blockers != null) {
            return ResponseEntity.status(e.status).contentType(MediaType.APPLICATION_JSON)
                    .body(new LeaseConflict(new ErrorBody(e.getMessage(), e.type), e.blockers));
        }
        return of(e.status, e.type, e.getMessage());
    }
}
