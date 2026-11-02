package com.kopnus.transfer.repository;

import com.kopnus.transfer.entity.AccountMutation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountMutationRepository extends JpaRepository<AccountMutation, Long> {
}
