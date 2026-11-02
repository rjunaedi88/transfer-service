package com.kopnus.transfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 50) String username,
        // BCrypt hanya memproses 72 byte pertama
        @NotBlank @Size(max = 72) String password) {
}
