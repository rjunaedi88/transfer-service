package com.kopnus.transfer.service;

import com.kopnus.transfer.dto.LoginRequest;
import com.kopnus.transfer.dto.LoginResponse;
import com.kopnus.transfer.entity.Customer;
import com.kopnus.transfer.exception.BusinessException;
import com.kopnus.transfer.exception.ErrorCode;
import com.kopnus.transfer.repository.CustomerRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String dummyHash;

    public AuthService(CustomerRepository customerRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(LoginRequest request) {
        Optional<Customer> customer = customerRepository.findByUsername(request.username());
        // BCrypt tetap dijalankan untuk username yang tidak ada, supaya waktu respons
        // tidak membocorkan username mana yang terdaftar
        String hash = customer.map(Customer::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (customer.isEmpty() || !passwordMatches) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return new LoginResponse(jwtService.generateToken(customer.get()), "Bearer", jwtService.expiresInSeconds());
    }
}
