package com.kopnus.transfer.dto;

import com.kopnus.transfer.entity.Transfer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        String status,
        String sourceAccountNumber,
        String destinationAccountNumber,
        BigDecimal amount,
        String description,
        Instant createdAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                "SUCCESS",
                transfer.getSourceAccountNumber(),
                transfer.getDestinationAccountNumber(),
                transfer.getAmount(),
                transfer.getDescription(),
                transfer.getCreatedAt());
    }
}
