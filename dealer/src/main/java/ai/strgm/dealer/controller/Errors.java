package ai.strgm.dealer.controller;

import static ai.strgm.util.Nulls.mapNullable;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import ai.strgm.dealer.api.model.ErrorBody;
import ai.strgm.dealer.api.model.ErrorResponse;
import ai.strgm.dealer.exception.DeckErrorException;
import ai.strgm.dealer.exception.DeckUnreachableException;
import ai.strgm.dealer.exception.LeaseUnavailableException;
import ai.strgm.dealer.exception.ModelNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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

    private static ResponseEntity<Object> errorResponse(final HttpStatusCode status, final String type, final String message,
            final @Nullable String param) {
        return ResponseEntity.status(status).contentType(APPLICATION_JSON)
                .body(new ErrorResponse(new ErrorBody(message, type).param(param)));
    }

    private static ResponseEntity<Object> invalidRequest(final HttpStatusCode status, final String message, final @Nullable String param) {
        return errorResponse(status, "invalid_request_error", message, param);
    }

    /** An exception's message, or its class name when it has none. */
    private static String message(final Throwable exception) {
        return exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handle(final ApiException exception) {
        return errorResponse(exception.status, exception.type, message(exception), exception.param);
    }

    @ExceptionHandler(ModelNotFoundException.class)
    public ResponseEntity<Object> handle(final ModelNotFoundException exception) {
        return errorResponse(NOT_FOUND, "model_not_found", message(exception), null);
    }

    /** A request that couldn't get a ready lease, or whose DECK server couldn't be reached. */
    @ExceptionHandler({LeaseUnavailableException.class, DeckUnreachableException.class})
    public ResponseEntity<Object> handleUnavailable(final RuntimeException exception) {
        return errorResponse(SERVICE_UNAVAILABLE, "server_error", message(exception), null);
    }

    /** DECK's own error, passed on with its status and body. */
    @ExceptionHandler(DeckErrorException.class)
    public ResponseEntity<Object> handle(final DeckErrorException exception) {
        final var responseBody = exception.deckErrorBody().isBlank()
                ? "{\"error\":{\"message\":\"DECK error " + exception.deckErrorStatus() + "\",\"type\":\"server_error\"}}" : exception.deckErrorBody();
        return ResponseEntity.status(exception.deckErrorStatus()).contentType(APPLICATION_JSON).body(responseBody);
    }

    /** A parameter outside its documented constraints, from method validation. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handle(final ConstraintViolationException exception) {
        final var firstPropertyPath = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath().toString())
                .sorted()
                .findFirst()
                .orElse(null);
        return invalidRequest(BAD_REQUEST, "Invalid request: " + exception.getMessage(),
                mapNullable(firstPropertyPath, propertyPath -> propertyPath.substring(propertyPath.lastIndexOf('.') + 1)));
    }

    /** Anything else: a 500, without the exception's details, which are logged. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handle(final Exception exception) {
        log.error("Unhandled error", exception);
        return errorResponse(INTERNAL_SERVER_ERROR, "server_error", "Internal server error", null);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(final HttpMessageNotReadableException exception,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        final var cause = exception.getMostSpecificCause();
        if (cause instanceof StreamReadException) {
            return invalidRequest(status, "The request body isn't valid JSON", null);
        }
        if (cause instanceof JacksonException mismatch) {
            final var fieldPath = fieldPathOf(mismatch);
            return invalidRequest(status, fieldPath == null ? "The request body doesn't match the expected schema"
                    : "The request body doesn't match the expected schema at " + fieldPath, fieldPath);
        }
        return invalidRequest(status, "The request body is missing or unreadable", null);
    }

    /** Where in the body the value Jackson couldn't read is, e.g. {@code messages[0].role}; null if Jackson didn't say. */
    private static @Nullable String fieldPathOf(final JacksonException exception) {
        final var fieldPath = new StringBuilder();
        exception.getPath().forEach(reference -> {
            if (reference.getPropertyName() != null) {
                fieldPath.append(fieldPath.isEmpty() ? "" : ".").append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                fieldPath.append('[').append(reference.getIndex()).append(']');
            }
        });
        return fieldPath.isEmpty() ? null : fieldPath.toString();
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
        return invalidRequest(status, "Invalid request body: " + details, errors.isEmpty() ? null : errors.getFirst().getField());
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(final TypeMismatchException exception, final HttpHeaders headers,
            final HttpStatusCode status, final WebRequest request) {
        final var parameterName = exception instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : exception.getPropertyName();
        return invalidRequest(status, "Invalid value for " + parameterName + ": " + exception.getValue(), parameterName);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(final MissingServletRequestParameterException exception,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        return invalidRequest(status, "Missing required parameter " + exception.getParameterName(), exception.getParameterName());
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(final ServletRequestBindingException exception,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        if (exception instanceof MissingRequestHeaderException missingHeader) {
            return invalidRequest(status, "Missing required header " + missingHeader.getHeaderName(), missingHeader.getHeaderName());
        }
        return invalidRequest(status, message(exception), null);
    }

    /** Every other exception Spring MVC raises, e.g. an unknown path (404) or a wrong method (405). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(final Exception exception, final @Nullable Object body,
            final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        final var type = status.isSameCodeAs(NOT_FOUND) ? "not_found" : status.is4xxClientError() ? "invalid_request_error" : "server_error";
        final var detail = exception instanceof NoResourceFoundException noResource ? "Unknown path: /" + noResource.getResourcePath()
                : exception instanceof org.springframework.web.ErrorResponse errorResponse ? errorResponse.getBody().getDetail() : null;
        final var message = detail != null ? detail : HttpStatus.valueOf(status.value()).getReasonPhrase();
        return ResponseEntity.status(status).headers(headers).contentType(APPLICATION_JSON)
                .body(new ErrorResponse(new ErrorBody(message, type)));
    }
}
