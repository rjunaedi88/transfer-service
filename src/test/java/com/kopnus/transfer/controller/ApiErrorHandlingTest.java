package com.kopnus.transfer.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kopnus.transfer.config.SecurityConfig;
import com.kopnus.transfer.dto.TransferRequest;
import com.kopnus.transfer.entity.Transfer;
import com.kopnus.transfer.exception.BusinessException;
import com.kopnus.transfer.exception.ErrorCode;
import com.kopnus.transfer.security.ErrorResponseWriter;
import com.kopnus.transfer.service.AuthService;
import com.kopnus.transfer.service.TransferResult;
import com.kopnus.transfer.service.TransferService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(
        controllers = {TransferController.class, AuthController.class},
        properties = {
                "app.jwt.secret=controller-test-secret-with-32-characters",
                "app.rate-limit.transfer.capacity=1000",
                "app.rate-limit.login.capacity=1000"
        })
@Import({SecurityConfig.class, ErrorResponseWriter.class})
class ApiErrorHandlingTest {

    private static final String URL = "/api/v1/transfers";
    private static final String VALID_BODY = """
            {"sourceAccountNumber":"1000000001","destinationAccountNumber":"1000000002",
             "amount":100.50,"description":"test"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    @MockitoBean
    private AuthService authService;

    private MockHttpServletRequestBuilder authenticatedTransfer(String body) {
        return post(URL)
                .with(jwt().jwt(token -> token.subject("1")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Transfer transfer() {
        return new Transfer(1L, "key-1", "hash", "1000000001", "1000000002", new BigDecimal("100.50"), "test");
    }

    @Test
    void newTransferReturns201() throws Exception {
        when(transferService.transfer(eq(1L), eq("key-1"), any(TransferRequest.class)))
                .thenReturn(TransferResult.created(transfer()));

        mockMvc.perform(authenticatedTransfer(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(100.50));
    }

    @Test
    void replayedTransferReturns200WithHeader() throws Exception {
        when(transferService.transfer(eq(1L), eq("key-1"), any(TransferRequest.class)))
                .thenReturn(new TransferResult(transfer(), true));

        mockMvc.perform(authenticatedTransfer(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        mockMvc.perform(post(URL)
                        .header("Authorization", "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post(URL)
                        .with(jwt().jwt(token -> token.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("Idempotency-Key")));
        verifyNoInteractions(transferService);
    }

    @Test
    void tooLongIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post(URL)
                        .with(jwt().jwt(token -> token.subject("1")))
                        .header("Idempotency-Key", "k".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(transferService);
    }

    @Test
    void negativeAmountReturns400WithFieldDetail() throws Exception {
        String body = VALID_BODY.replace("100.50", "-1");

        mockMvc.perform(authenticatedTransfer(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasItem(startsWith("amount:"))));
        verifyNoInteractions(transferService);
    }

    @Test
    void amountWithThreeDecimalsReturns400() throws Exception {
        mockMvc.perform(authenticatedTransfer(VALID_BODY.replace("100.50", "100.555")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(authenticatedTransfer("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void businessExceptionUsesItsStatusAndCode() throws Exception {
        when(transferService.transfer(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_BALANCE));

        mockMvc.perform(authenticatedTransfer(VALID_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.path").value(URL));
    }

    @Test
    void lockTimeoutReturns503WithRetryAfter() throws Exception {
        when(transferService.transfer(any(), any(), any()))
                .thenThrow(new CannotAcquireLockException("lock timeout"));

        mockMvc.perform(authenticatedTransfer(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"));
    }

    @Test
    void unexpectedExceptionDoesNotLeakDetails() throws Exception {
        when(transferService.transfer(any(), any(), any()))
                .thenThrow(new IllegalStateException("jdbc password is hunter2"));

        mockMvc.perform(authenticatedTransfer(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    @Test
    void wrongMethodReturns405InSameFormat() throws Exception {
        mockMvc.perform(get(URL).with(jwt()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void loginDoesNotRequireToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
