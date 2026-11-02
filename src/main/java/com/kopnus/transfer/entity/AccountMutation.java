package com.kopnus.transfer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_mutations")
public class AccountMutation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MutationType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountMutation() {
    }

    public AccountMutation(UUID transferId, Account account, MutationType type, BigDecimal amount) {
        this.transferId = transferId;
        this.accountNumber = account.getAccountNumber();
        this.type = type;
        this.amount = amount;
        this.balanceAfter = account.getBalance();
        this.createdAt = Instant.now();
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public MutationType getType() {
        return type;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }
}
