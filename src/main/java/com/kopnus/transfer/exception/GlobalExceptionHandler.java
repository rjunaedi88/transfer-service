package com.kopnus.transfer.exception;

import com.kopnus.transfer.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        return toResponse(ex.getErrorCode(), request);
    }

    // Kunci akun habis waktu, deadlock, atau koneksi database penuh. Transaksi sudah
    // di-rollback, jadi client aman mengulang dengan Idempotency-Key yang sama.
    @ExceptionHandler({
            TransientDataAccessException.class,
            TransactionTimedOutException.class,
            CannotCreateTransactionException.class
    })
    ResponseEntity<ErrorResponse> handleBusy(Exception ex, HttpServletRequest request) {
        log.warn("Request {} could not be completed: {}", request.getRequestURI(), ex.getMessage());
        ErrorCode code = ErrorCode.SERVICE_BUSY;
        return ResponseEntity.status(code.getStatus())
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ErrorResponse.of(code.name(), code.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        return toResponse(ErrorCode.INTERNAL_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationError(ex.getBindingResult().getAllErrors(), headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationError(ex.getAllErrors(), headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorResponse body = ErrorResponse.of(ErrorCode.VALIDATION_ERROR.name(),
                "Request body is missing or malformed", pathOf(request));
        return ResponseEntity.badRequest().headers(headers).body(body);
    }

    // Menyeragamkan format semua error bawaan Spring MVC (404, 405, 415, header hilang, dll)
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        if (status.is5xxServerError()) {
            log.error("Error on {}", pathOf(request), ex);
        }
        ErrorResponse error = ErrorResponse.of(status.name(), status.getReasonPhrase(), pathOf(request));
        if (status == HttpStatus.BAD_REQUEST) {
            error = ErrorResponse.of(ErrorCode.VALIDATION_ERROR.name(), ex.getMessage(), pathOf(request));
        }
        return ResponseEntity.status(status).headers(headers).body(error);
    }

    private ResponseEntity<Object> validationError(List<? extends MessageSourceResolvable> errors,
            HttpHeaders headers, WebRequest request) {
        List<String> details = errors.stream().map(GlobalExceptionHandler::describe).toList();
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity.badRequest().headers(headers)
                .body(new ErrorResponse(code.name(), code.getMessage(), details, pathOf(request), Instant.now()));
    }

    private static String describe(MessageSourceResolvable error) {
        return error instanceof FieldError fieldError
                ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                : error.getDefaultMessage();
    }

    private ResponseEntity<ErrorResponse> toResponse(ErrorCode code, HttpServletRequest request) {
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), code.getMessage(), request.getRequestURI()));
    }

    private static String pathOf(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }
}
