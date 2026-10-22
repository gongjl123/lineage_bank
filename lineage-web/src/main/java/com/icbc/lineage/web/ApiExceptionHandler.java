package com.icbc.lineage.web;

import com.icbc.lineage.app.ImportJobs;
import com.icbc.lineage.app.UploadException;
import com.icbc.lineage.domain.exception.DataValidationException;
import com.icbc.lineage.infrastructure.storage.StorageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DataValidationException.class)
    public ResponseEntity<Map<String, Object>> validation(DataValidationException exception) {
        return ResponseEntity.unprocessableEntity().body(result("detail", exception.getMessage(),
                "issues", exception.getIssues()));
    }

    @ExceptionHandler(ImportJobs.JobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> jobNotFound(ImportJobs.JobNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result("detail", exception.getMessage()));
    }

    @ExceptionHandler(UploadException.class)
    public ResponseEntity<Map<String, Object>> upload(UploadException exception) {
        return ResponseEntity.status(exception.status()).body(result("detail", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result("detail", exception.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception exception) {
        return ResponseEntity.unprocessableEntity().body(result("detail", exception.getMessage()));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Map<String, Object>> storage(StorageException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(result("detail", "Database operation failed", "error", exception.getMessage()));
    }

    private Map<String, Object> result(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
