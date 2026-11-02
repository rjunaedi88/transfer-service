package com.kopnus.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kopnus.transfer.dto.TransferRequest;
import com.kopnus.transfer.entity.Transfer;
import com.kopnus.transfer.exception.BusinessException;
import com.kopnus.transfer.exception.ErrorCode;
import com.kopnus.transfer.repository.TransferRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final String KEY = "key-1";

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private TransferExecutor transferExecutor;

    @InjectMocks
    private TransferService transferService;

    private static TransferRequest request(String amount) {
        return new TransferRequest("1000000001", "1000000002", new BigDecimal(amount), "test");
    }

    private Transfer storedTransfer(String requestHash) {
        return new Transfer(CUSTOMER_ID, KEY, requestHash, "1000000001", "1000000002", BigDecimal.TEN, "test");
    }

    private String hashOf(TransferRequest request) {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        when(transferRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, KEY)).thenReturn(Optional.empty());
        when(transferExecutor.execute(eq(CUSTOMER_ID), eq(KEY), hash.capture(), eq(request)))
                .thenReturn(TransferResult.created(storedTransfer("x")));
        transferService.transfer(CUSTOMER_ID, KEY, request);
        return hash.getValue();
    }

    @Test
    void newKeyExecutesTransfer() {
        TransferRequest request = request("100");
        Transfer transfer = storedTransfer("hash");
        when(transferRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, KEY)).thenReturn(Optional.empty());
        when(transferExecutor.execute(eq(CUSTOMER_ID), eq(KEY), anyString(), eq(request)))
                .thenReturn(TransferResult.created(transfer));

        TransferResult result = transferService.transfer(CUSTOMER_ID, KEY, request);

        assertThat(result.replayed()).isFalse();
        assertThat(result.transfer()).isSameAs(transfer);
    }

    @Test
    void sameKeyAndSameBodyReturnsStoredTransferWithoutMovingMoney() {
        TransferRequest request = request("100");
        Transfer stored = storedTransfer(hashOf(request));
        when(transferRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, KEY)).thenReturn(Optional.of(stored));

        TransferResult result = transferService.transfer(CUSTOMER_ID, KEY, request);

        assertThat(result.replayed()).isTrue();
        assertThat(result.transfer()).isSameAs(stored);
        // Satu-satunya pemanggilan berasal dari hashOf() saat menyiapkan test
        verify(transferExecutor, times(1)).execute(any(), any(), any(), any());
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        when(transferRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, KEY))
                .thenReturn(Optional.of(storedTransfer("hash-of-another-request")));

        assertThatThrownBy(() -> transferService.transfer(CUSTOMER_ID, KEY, request("100")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
        verify(transferExecutor, never()).execute(any(), any(), any(), any());
    }

    @Test
    void amountScaleDoesNotChangeRequestHash() {
        String first = hashOf(request("100"));
        String second = hashOf(request("100.00"));

        assertThat(first).isEqualTo(second).hasSize(64);
    }

    @Test
    void differentAmountChangesRequestHash() {
        assertThat(hashOf(request("100"))).isNotEqualTo(hashOf(request("101")));
    }

    @Test
    void transferToSameAccountIsRejected() {
        TransferRequest request = new TransferRequest("1000000001", "1000000001", BigDecimal.TEN, null);

        assertThatThrownBy(() -> transferService.transfer(CUSTOMER_ID, KEY, request))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SAME_ACCOUNT));
        verify(transferExecutor, never()).execute(any(), any(), any(), any());
    }

    @Test
    void losingUniqueConstraintRaceReturnsWinnerResult() {
        TransferRequest request = request("100");
        Transfer winner = storedTransfer(hashOf(request));
        when(transferRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(transferExecutor.execute(eq(CUSTOMER_ID), eq(KEY), anyString(), eq(request)))
                .thenThrow(new DataIntegrityViolationException("uq_transfers_idempotency"));

        TransferResult result = transferService.transfer(CUSTOMER_ID, KEY, request);

        assertThat(result.replayed()).isTrue();
        assertThat(result.transfer()).isSameAs(winner);
    }

    @Test
    void otherIntegrityViolationIsNotSwallowed() {
        TransferRequest request = request("100");
        DataIntegrityViolationException violation = new DataIntegrityViolationException("chk_accounts_balance");
        when(transferRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, KEY)).thenReturn(Optional.empty());
        when(transferExecutor.execute(eq(CUSTOMER_ID), eq(KEY), anyString(), eq(request))).thenThrow(violation);

        assertThatThrownBy(() -> transferService.transfer(CUSTOMER_ID, KEY, request)).isSameAs(violation);
    }
}
