package com.centinela.api.infrastructure.adapter.inbound.web;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centraliza el manejo de errores de la API.
 *
 * Anti-polución de payloads (sección 4.2 del TDD): cualquier campo no
 * contemplado en el contrato (UnrecognizedPropertyException) o cualquier
 * violación de las anotaciones @Valid del DTO de entrada resulta en
 * 422 Unprocessable Entity.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof UnrecognizedPropertyException upe) {
            return unprocessable("Campo no reconocido en el payload: '" + upe.getPropertyName() + "'");
        }
        return badRequest("Payload JSON malformado: " + ex.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        Map<String, Object> body = baseBody(HttpStatus.UNPROCESSABLE_ENTITY, "Error de validación del contrato");
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return unprocessable(ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> unprocessable(String message) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(baseBody(HttpStatus.UNPROCESSABLE_ENTITY, message));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(baseBody(HttpStatus.BAD_REQUEST, message));
    }

    private Map<String, Object> baseBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
