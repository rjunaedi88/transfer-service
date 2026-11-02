package com.kopnus.transfer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<String> details, String path, Instant timestamp) {

    public static ErrorResponse of(String code, String message, String path) {
        return new ErrorResponse(code, message, null, path, Instant.now());
    }
}
