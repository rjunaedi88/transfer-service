package com.kopnus.transfer.service;

import com.kopnus.transfer.dto.TransferRequest;
import com.kopnus.transfer.exception.BusinessException;
import com.kopnus.transfer.exception.ErrorCode;
import com.kopnus.transfer.repository.TransferRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Mengatur duplicate request protection. Perpindahan uangnya ada di TransferExecutor,
 * kelas terpisah supaya @Transactional bekerja dan transaksi sudah di-rollback
 * saat pelanggaran unique constraint ditangkap di sini.
 */
@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final TransferExecutor transferExecutor;

    public TransferService(TransferRepository transferRepository, TransferExecutor transferExecutor) {
        this.transferRepository = transferRepository;
        this.transferExecutor = transferExecutor;
    }

    public TransferResult transfer(Long customerId, String idempotencyKey, TransferRequest request) {
        if (request.sourceAccountNumber().equals(request.destinationAccountNumber())) {
            throw new BusinessException(ErrorCode.SAME_ACCOUNT);
        }

        String requestHash = hash(request);
        var existing = transferRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (existing.isPresent()) {
            return TransferResult.replayOf(existing.get(), requestHash);
        }

        try {
            return transferExecutor.execute(customerId, idempotencyKey, requestHash, request);
        } catch (DataIntegrityViolationException e) {
            // Kalah balapan dengan request lain ber-key sama: transaksi ini sudah batal,
            // jadi kembalikan hasil request pemenang. Pelanggaran lain tetap dilempar.
            return transferRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                    .map(winner -> TransferResult.replayOf(winner, requestHash))
                    .orElseThrow(() -> e);
        }
    }

    private String hash(TransferRequest request) {
        String canonical = String.join("|",
                request.sourceAccountNumber(),
                request.destinationAccountNumber(),
                // 100, 100.0 dan 100.00 adalah nominal yang sama
                request.amount().stripTrailingZeros().toPlainString(),
                Objects.toString(request.description(), ""));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
