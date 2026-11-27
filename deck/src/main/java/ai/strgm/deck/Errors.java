package ai.strgm.deck;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

import ai.strgm.deck.api.model.ErrorBody;
import ai.strgm.deck.api.model.ErrorResponse;
import ai.strgm.deck.api.model.LeaseConflict;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;

/**
 * Every error DECK returns has the documented OpenAI-style body, including the ones Spring MVC raises (an unreadable
 * body, a bad parameter, an unknown path, a wrong method) and unexpected failures, which become a 500.
 */
@RestControllerAdvice
public class Errors extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(Errors.class);

    static ResponseEntity<Object> of(final HttpStatusCode status, final String type, final String message, final @Nullable String param) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(new ErrorBody(message, type).param(param)));
    }

    private static ResponseEntity<Object> invalid(final HttpStatusCode status, final String message, final @Nullable String param) {
        return of(status, "invalid_request_error", message, param);
    }

    /** An exception's message, or its class name when it has none. */
    private static String message(final Throwable exception) {
        return exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
    }

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<String> handle(final UpstreamException exception) {
        final var response = ResponseEntity.status(exception.status).contentType(MediaType.APPLICATION_JSON);
        if (exception.retryAfter != null) {
            response.header("Retry-After", exception.retryAfter);
        }
        return response.body(exception.body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handle(final ApiException exception) {
        final var blockers = exception.blockers;
        if (blockers != null) {
            return ResponseEntity.status(exception.status).contentType(MediaType.APPLICATION_JSON)
                    .body(new LeaseConflict(new ErrorBody(message(exception), exception.type), blockers));
        }
        return of(exception.status, exception.type, message(exception), null);
    }

    /** A parameter outside its documented constraints, from method validation. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handle(final ConstraintViolationException exception) {
        final var first = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath().toString())
                .sorted()
                .findFirst()
                .orElse(null);
        return invalid(HttpStatus.BAD_REQUEST, "Invalid request: " + exception.getMessage(),
                first == null ? null : first.substring(first.lastIndexOf('.') + 1));
    }

    /** Anything else: a 500, without the exception's details, which are logged. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handle(final Exception exception) {
        log.error("Unhandled error", exception);
        return of(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", "Internal server error", null);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(final HttpMessageNotReadableException exception,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        final var cause = exception.getMostSpecificCause();
        if (cause instanceof StreamReadException) {
            return invalid(status, "The request body isn't valid JSON", null);
        }
        if (cause instanceof JacksonException mismatch) {
            final var field = field(mismatch);
            return invalid(status, field == null ? "The request body doesn't match the expected schema"
                    : "The request body doesn't match the expected schema at " + field, field);
        }
        return invalid(status, "The request body is missing or unreadable", null);
    }

    /** Where in the body the value Jackson couldn't read is, e.g. {@code messages[0].role}; null if Jackson didn't say. */
    private static @Nullable String field(final JacksonException exception) {
        final var path = new StringBuilder();
        for (final var reference : exception.getPath()) {
            if (reference.getPropertyName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? null : path.toString();
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(final MethodArgumentNotValidException exception,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        final var errors = exception.getBindingResult().getFieldErrors().stream()
                .sorted(comparing(FieldError::getField))
                .toList();
        final var details = errors.stream()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .collect(joining("; "));
        return invalid(status, "Invalid request body: " + details, errors.isEmpty() ? null : errors.getFirst().getField());
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(final TypeMismatchException exception, final HttpHeaders headers,
            final HttpStatusCode status, final WebRequest request) {
        final var name = exception instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : exception.getPropertyName();
        return invalid(status, "Invalid value for " + name + ": " + exception.getValue(), name);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(final MissingServletRequestParameterException exception,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        return invalid(status, "Missing required parameter " + exception.getParameterName(), exception.getParameterName());
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(final ServletRequestBindingException exception,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        if (exception instanceof MissingRequestHeaderException missingHeader) {
            return invalid(status, "Missing required header " + missingHeader.getHeaderName(), missingHeader.getHeaderName());
        }
        return invalid(status, message(exception), null);
    }

    /** Every other exception Spring MVC raises, e.g. an unknown path (404) or a wrong method (405). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(final Exception exception, final @Nullable Object body,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        final var type = status.isSameCodeAs(HttpStatus.NOT_FOUND) ? "not_found" : status.is4xxClientError() ? "invalid_request_error" : "server_error";
        final var detail = exception instanceof NoResourceFoundException noResource ? "Unknown path: /" + noResource.getResourcePath()
                : exception instanceof org.springframework.web.ErrorResponse errorResponse ? errorResponse.getBody().getDetail() : null;
        final var message = detail != null ? detail : HttpStatus.valueOf(status.value()).getReasonPhrase();
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(new ErrorBody(message, type)));
    }
}
