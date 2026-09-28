package com.microservice.pro.payment_service.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * IdempotencyRepository — Session 22 (Lab 18 Task 2).
 */
@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    List<IdempotencyRecord> findByCreatedAtBefore(Instant cutoff);
}
