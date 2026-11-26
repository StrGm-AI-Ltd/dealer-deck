package ai.strgm.dealer;

import ai.strgm.dealer.api.model.ErrorBody;
import ai.strgm.dealer.api.model.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.stream.Collectors;
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
 * Every error DEALER returns has the documented OpenAI-style body, including the ones Spring MVC raises (an
 * unreadable body, a bad header, an unknown path, a wrong method) and unexpected failures, which become a 500.
 */
@RestControllerAdvice
public class Errors extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(Errors.class);

    private static ResponseEntity<Object> of(final HttpStatusCode status, final String type, final String message,
            final @Nullable String param) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(new ErrorBody(message, type).param(param)));
    }

    private static ResponseEntity<Object> invalid(final HttpStatusCode status, final String message, final @Nullable String param) {
        return of(status, "invalid_request_error", message, param);
    }

    /** An exception's message, or its class name when it has none. */
    private static String message(final Throwable exception) {
        final var message = exception.getMessage();
        return message != null ? message : exception.getClass().getSimpleName();
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handle(final ApiException exception) {
        final var raw = exception.body;
        if (raw != null) {
            return ResponseEntity.status(exception.status).contentType(MediaType.APPLICATION_JSON).body(raw);
        }
        final var type = exception.type;
        return of(exception.status, type != null ? type : "server_error", message(exception), exception.param);
    }

    /** A parameter outside its documented constraints, from method validation. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handle(final ConstraintViolationException exception) {
        final var first = exception.getConstraintViolations().stream().map(violation -> violation.getPropertyPath().toString())
                .sorted().findFirst().orElse(null);
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
    protected ResponseEntity<Object> handleHttpMessageNotReadable(final HttpMessageNotReadableException ex,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        final var cause = ex.getMostSpecificCause();
        return invalid(status, cause instanceof StreamReadException ? "The request body isn't valid JSON"
                : cause instanceof JacksonException ? "The request body doesn't match the expected schema"
                : "The request body is missing or unreadable", null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(final MethodArgumentNotValidException ex,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        final var errors = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField)).toList();
        return invalid(status, "Invalid request body: " + errors.stream()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage()).collect(Collectors.joining("; ")),
                errors.isEmpty() ? null : errors.getFirst().getField());
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(final TypeMismatchException ex, final HttpHeaders headers,
            final HttpStatusCode status, final WebRequest request) {
        final var name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : ex.getPropertyName();
        return invalid(status, "Invalid value for " + name + ": " + ex.getValue(), name);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(final MissingServletRequestParameterException ex,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        return invalid(status, "Missing required parameter " + ex.getParameterName(), ex.getParameterName());
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(final ServletRequestBindingException ex,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missingHeader) {
            return invalid(status, "Missing required header " + missingHeader.getHeaderName(), missingHeader.getHeaderName());
        }
        return invalid(status, message(ex), null);
    }

    /** Every other exception Spring MVC raises, e.g. an unknown path (404) or a wrong method (405). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(final Exception ex, final @Nullable Object body,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        final var type = status.isSameCodeAs(HttpStatus.NOT_FOUND) ? "not_found" : status.is4xxClientError() ? "invalid_request_error" : "server_error";
        final var detail = ex instanceof NoResourceFoundException noResource ? "Unknown path: /" + noResource.getResourcePath()
                : ex instanceof org.springframework.web.ErrorResponse errorResponse ? errorResponse.getBody().getDetail() : null;
        final var message = detail != null ? detail : HttpStatus.valueOf(status.value()).getReasonPhrase();
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(new ErrorBody(message, type)));
    }
}
