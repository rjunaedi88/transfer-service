package com.kopnus.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kopnus.transfer.dto.TransferRequest;
import com.kopnus.transfer.entity.Account;
import com.kopnus.transfer.entity.AccountMutation;
import com.kopnus.transfer.entity.AccountStatus;
import com.kopnus.transfer.entity.MutationType;
import com.kopnus.transfer.entity.Transfer;
import com.kopnus.transfer.exception.BusinessException;
import com.kopnus.transfer.exception.ErrorCode;
import com.kopnus.transfer.repository.AccountMutationRepository;
import com.kopnus.transfer.repository.AccountRepository;
import com.kopnus.transfer.repository.TransferRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransferExecutorTest {

    private static final Long OWNER = 1L;
    private static final Long OTHER_CUSTOMER = 2L;
    private static final String LOWER = "1000000001";
    private static final String HIGHER = "1000000002";
    private static final String KEY = "key-1";
    private static final String HASH = "hash";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountMutationRepository mutationRepository;

    @InjectMocks
    private TransferExecutor executor;

    @Captor
    private ArgumentCaptor<List<AccountMutation>> mutations;

    private Account lowerAccount;
    private Account higherAccount;

    @BeforeEach
    void setUp() {
        lowerAccount = new Account(1L, LOWER, OWNER, new BigDecimal("1000.00"), AccountStatus.ACTIVE);
        higherAccount = new Account(2L, HIGHER, OTHER_CUSTOMER, new BigDecimal("500.00"), AccountStatus.ACTIVE);
        lenient().when(accountRepository.findByAccountNumberForUpdate(LOWER)).thenReturn(Optional.of(lowerAccount));
        lenient().when(accountRepository.findByAccountNumberForUpdate(HIGHER)).thenReturn(Optional.of(higherAccount));
        lenient().when(transferRepository.findByCustomerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.empty());
        lenient().when(transferRepository.saveAndFlush(any())).then(returnsFirstArg());
    }

    private TransferResult execute(String source, String destination, String amount) {
        return executor.execute(OWNER, KEY, HASH, new TransferRequest(source, destination, new BigDecimal(amount), "test"));
    }

    private void assertRejected(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e ->
                assertThat(e.getErrorCode()).isEqualTo(expected));
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void movesMoneyAndWritesBothMutations() {
        TransferResult result = execute(LOWER, HIGHER, "250.50");

        assertThat(result.replayed()).isFalse();
        assertThat(lowerAccount.getBalance()).isEqualByComparingTo("749.50");
        assertThat(higherAccount.getBalance()).isEqualByComparingTo("750.50");

        verify(mutationRepository).saveAll(mutations.capture());
        assertThat(mutations.getValue()).hasSize(2);
        AccountMutation debit = mutations.getValue().get(0);
        AccountMutation credit = mutations.getValue().get(1);
        assertThat(debit.getType()).isEqualTo(MutationType.DEBIT);
        assertThat(debit.getAccountNumber()).isEqualTo(LOWER);
        assertThat(debit.getBalanceAfter()).isEqualByComparingTo("749.50");
        assertThat(credit.getType()).isEqualTo(MutationType.CREDIT);
        assertThat(credit.getAccountNumber()).isEqualTo(HIGHER);
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo("750.50");
    }

    @Test
    void locksAccountsInSameOrderRegardlessOfTransferDirection() {
        higherAccount = new Account(2L, HIGHER, OWNER, new BigDecimal("500.00"), AccountStatus.ACTIVE);
        when(accountRepository.findByAccountNumberForUpdate(HIGHER)).thenReturn(Optional.of(higherAccount));

        execute(HIGHER, LOWER, "100");

        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findByAccountNumberForUpdate(LOWER);
        order.verify(accountRepository).findByAccountNumberForUpdate(HIGHER);
    }

    @Test
    void transferOfExactBalanceIsAllowed() {
        execute(LOWER, HIGHER, "1000.00");

        assertThat(lowerAccount.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void insufficientBalanceIsRejected() {
        assertRejected(ErrorCode.INSUFFICIENT_BALANCE, () -> execute(LOWER, HIGHER, "1000.01"));
        assertThat(higherAccount.getBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void sourceAccountOfAnotherCustomerIsRejected() {
        assertRejected(ErrorCode.ACCOUNT_ACCESS_DENIED, () -> execute(HIGHER, LOWER, "1"));
    }

    @Test
    void blockedSourceAccountIsRejected() {
        lowerAccount = new Account(1L, LOWER, OWNER, new BigDecimal("1000.00"), AccountStatus.BLOCKED);
        when(accountRepository.findByAccountNumberForUpdate(LOWER)).thenReturn(Optional.of(lowerAccount));

        assertRejected(ErrorCode.ACCOUNT_INACTIVE, () -> execute(LOWER, HIGHER, "1"));
    }

    @Test
    void blockedDestinationAccountIsRejected() {
        higherAccount = new Account(2L, HIGHER, OTHER_CUSTOMER, BigDecimal.ZERO, AccountStatus.BLOCKED);
        when(accountRepository.findByAccountNumberForUpdate(HIGHER)).thenReturn(Optional.of(higherAccount));

        assertRejected(ErrorCode.ACCOUNT_INACTIVE, () -> execute(LOWER, HIGHER, "1"));
        assertThat(lowerAccount.getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void unknownAccountIsRejected() {
        when(accountRepository.findByAccountNumberForUpdate(HIGHER)).thenReturn(Optional.empty());

        assertRejected(ErrorCode.ACCOUNT_NOT_FOUND, () -> execute(LOWER, HIGHER, "1"));
    }

    @Test
    void twinRequestThatWaitedForLockReplaysWinnerEvenIfBalanceIsGone() {
        lowerAccount.debit(new BigDecimal("1000.00"));
        Transfer winner = new Transfer(OWNER, KEY, HASH, LOWER, HIGHER, new BigDecimal("1000.00"), "test");
        when(transferRepository.findByCustomerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.of(winner));

        TransferResult result = execute(LOWER, HIGHER, "1000.00");

        assertThat(result.replayed()).isTrue();
        assertThat(result.transfer()).isSameAs(winner);
        verify(transferRepository, never()).saveAndFlush(any());
    }
}
