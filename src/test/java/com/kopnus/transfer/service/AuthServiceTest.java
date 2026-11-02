package com.kopnus.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kopnus.transfer.dto.LoginRequest;
import com.kopnus.transfer.dto.LoginResponse;
import com.kopnus.transfer.entity.Customer;
import com.kopnus.transfer.exception.BusinessException;
import com.kopnus.transfer.exception.ErrorCode;
import com.kopnus.transfer.repository.CustomerRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(customerRepository, passwordEncoder, jwtService);
        Customer budi = new Customer(1L, "budi", passwordEncoder.encode("secret"));
        when(customerRepository.findByUsername("budi")).thenReturn(Optional.of(budi));
        when(customerRepository.findByUsername("ghost")).thenReturn(Optional.empty());
    }

    private void assertInvalidCredentials(LoginRequest request) {
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void correctPasswordReturnsToken() {
        when(jwtService.generateToken(any())).thenReturn("token");
        when(jwtService.expiresInSeconds()).thenReturn(900L);

        LoginResponse response = authService.login(new LoginRequest("budi", "secret"));

        assertThat(response).isEqualTo(new LoginResponse("token", "Bearer", 900L));
    }

    @Test
    void wrongPasswordIsRejected() {
        assertInvalidCredentials(new LoginRequest("budi", "wrong"));
    }

    @Test
    void unknownUsernameIsRejectedTheSameWay() {
        assertInvalidCredentials(new LoginRequest("ghost", "secret"));
    }
}
