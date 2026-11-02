package com.kopnus.transfer.dto;

public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
}
