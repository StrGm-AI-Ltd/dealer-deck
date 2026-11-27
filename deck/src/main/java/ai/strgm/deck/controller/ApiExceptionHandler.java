package ai.strgm.deck.controller;

import static ai.strgm.util.Nulls.mapNullable;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import ai.strgm.deck.exception.LeaseConflictException;
import ai.strgm.deck.exception.LeaseInactiveException;
import ai.strgm.deck.exception.ProviderCredentialMissingException;
import ai.strgm.deck.exception.ProviderErrorException;
import ai.strgm.deck.exception.ProviderUnreachableException;
import ai.strgm.deck.exception.UnknownLeaseException;
import ai.strgm.deck.exception.UnknownProfileException;
import ai.strgm.deck.mapper.DeckApiMapper;
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
import org.springframework.web.ErrorResponse;
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
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static ResponseEntity<Object> errorResponse(final HttpStatusCode status, final String type, final String message, final @Nullable String param) {
        return ResponseEntity.status(status).contentType(APPLICATION_JSON)
                .body(DeckApiMapper.errorResponse(message, type, param));
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
        return errorResponse(exception.status, exception.type, message(exception), null);
    }

    @ExceptionHandler({UnknownProfileException.class, UnknownLeaseException.class})
    public ResponseEntity<Object> handleUnknown(final RuntimeException exception) {
        return errorResponse(NOT_FOUND, "not_found", message(exception), null);
    }

    @ExceptionHandler(LeaseInactiveException.class)
    public ResponseEntity<Object> handle(final LeaseInactiveException exception) {
        return errorResponse(CONFLICT, "lease_inactive", message(exception), null);
    }

    @ExceptionHandler(LeaseConflictException.class)
    public ResponseEntity<Object> handle(final LeaseConflictException exception) {
        return ResponseEntity.status(CONFLICT).contentType(APPLICATION_JSON)
                .body(DeckApiMapper.leaseConflict(message(exception), "lease_conflict", exception.blockers()));
    }

    @ExceptionHandler(ProviderCredentialMissingException.class)
    public ResponseEntity<Object> handle(final ProviderCredentialMissingException exception) {
        return errorResponse(UNAUTHORIZED, "authentication_error",
                "Missing X-Provider-Authorization: this DECK server forwards the provider credential it receives (D104)", null);
    }

    @ExceptionHandler(ProviderUnreachableException.class)
    public ResponseEntity<Object> handle(final ProviderUnreachableException exception) {
        return errorResponse(GATEWAY_TIMEOUT, "upstream_error", message(exception), null);
    }

    /** The provider's own error, passed on with its body and any Retry-After, under the status its reason maps to. */
    @ExceptionHandler(ProviderErrorException.class)
    public ResponseEntity<Object> handle(final ProviderErrorException exception) {
        final var responseStatus = switch (exception.reason()) {
            case RATE_LIMITED -> TOO_MANY_REQUESTS;
            case CREDENTIAL_REJECTED -> UNAUTHORIZED;
            case REQUEST_REJECTED -> BAD_REQUEST;
            case UNAVAILABLE -> SERVICE_UNAVAILABLE;
            case UNREADABLE -> BAD_GATEWAY;
        };
        if (exception.reason() == ProviderErrorException.Reason.UNREADABLE) {
            return errorResponse(responseStatus, "upstream_error", message(exception), null);
        }
        final var responseBuilder = ResponseEntity.status(responseStatus).contentType(APPLICATION_JSON);
        final var retryAfter = exception.retryAfter();
        if (retryAfter != null) {
            responseBuilder.header("Retry-After", retryAfter);
        }
        return responseBuilder.body(exception.providerErrorBody().isBlank()
                ? "{\"error\":{\"message\":\"" + message(exception) + "\",\"type\":\"upstream_error\"}}" : exception.providerErrorBody());
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
                : exception instanceof ErrorResponse errorResponse ? errorResponse.getBody().getDetail() : null;
        final var message = detail != null ? detail : HttpStatus.valueOf(status.value()).getReasonPhrase();
        return ResponseEntity.status(status).headers(headers).contentType(APPLICATION_JSON)
                .body(DeckApiMapper.errorResponse(message, type, null));
    }
}
