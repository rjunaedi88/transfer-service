package com.kopnus.transfer.service;

import com.kopnus.transfer.entity.Transfer;
import com.kopnus.transfer.exception.BusinessException;
import com.kopnus.transfer.exception.ErrorCode;

/** @param replayed true jika hasil diambil dari transfer lama, tanpa memindahkan uang lagi */
public record TransferResult(Transfer transfer, boolean replayed) {

    public static TransferResult created(Transfer transfer) {
        return new TransferResult(transfer, false);
    }

    /** Key yang sama hanya boleh diulang dengan isi request yang sama. */
    public static TransferResult replayOf(Transfer existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return new TransferResult(existing, true);
    }
}
