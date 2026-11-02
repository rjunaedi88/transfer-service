package com.kopnus.transfer.service;

import com.kopnus.transfer.dto.TransferRequest;
import com.kopnus.transfer.entity.Account;
import com.kopnus.transfer.entity.AccountMutation;
import com.kopnus.transfer.entity.MutationType;
import com.kopnus.transfer.entity.Transfer;
import com.kopnus.transfer.exception.BusinessException;
import com.kopnus.transfer.exception.ErrorCode;
import com.kopnus.transfer.repository.AccountMutationRepository;
import com.kopnus.transfer.repository.AccountRepository;
import com.kopnus.transfer.repository.TransferRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransferExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransferExecutor.class);

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final AccountMutationRepository mutationRepository;

    public TransferExecutor(AccountRepository accountRepository, TransferRepository transferRepository,
            AccountMutationRepository mutationRepository) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.mutationRepository = mutationRepository;
    }

    @Transactional(timeout = 10)
    public TransferResult execute(Long customerId, String idempotencyKey, String requestHash, TransferRequest request) {
        Map<String, Account> accounts = lockAccounts(request);

        // Dicek ulang setelah dapat kunci: request kembar yang tadi menunggu kunci harus
        // melihat hasil pemenang, bukan gagal karena saldonya sudah terpotong oleh pemenang.
        Optional<Transfer> existing = transferRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (existing.isPresent()) {
            return TransferResult.replayOf(existing.get(), requestHash);
        }

        Account source = accounts.get(request.sourceAccountNumber());
        Account destination = accounts.get(request.destinationAccountNumber());
        if (!source.isOwnedBy(customerId)) {
            throw new BusinessException(ErrorCode.ACCOUNT_ACCESS_DENIED);
        }
        if (!source.isActive() || !destination.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE);
        }

        source.debit(request.amount());
        destination.credit(request.amount());

        Transfer transfer = transferRepository.saveAndFlush(new Transfer(customerId, idempotencyKey, requestHash,
                request.sourceAccountNumber(), request.destinationAccountNumber(), request.amount(),
                request.description()));
        mutationRepository.saveAll(List.of(
                new AccountMutation(transfer.getId(), source, MutationType.DEBIT, request.amount()),
                new AccountMutation(transfer.getId(), destination, MutationType.CREDIT, request.amount())));

        log.info("Transfer {} completed", transfer.getId());
        return TransferResult.created(transfer);
    }

    // Selalu dikunci berurutan menurut nomor rekening. Tanpa ini, transfer A->B dan B->A
    // yang berjalan bersamaan bisa saling menunggu (deadlock).
    private Map<String, Account> lockAccounts(TransferRequest request) {
        return Stream.of(request.sourceAccountNumber(), request.destinationAccountNumber())
                .sorted()
                .map(number -> accountRepository.findByAccountNumberForUpdate(number)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND)))
                .collect(Collectors.toMap(Account::getAccountNumber, Function.identity()));
    }
}
