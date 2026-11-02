package com.kopnus.transfer.repository;

import com.kopnus.transfer.entity.Transfer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);
}
