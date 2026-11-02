package com.kopnus.transfer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TransferRequest(
        @NotNull @Pattern(regexp = "\\d{10,20}", message = "must be 10-20 digits") String sourceAccountNumber,
        @NotNull @Pattern(regexp = "\\d{10,20}", message = "must be 10-20 digits") String destinationAccountNumber,
        @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
        @Size(max = 140) String description) {
}
