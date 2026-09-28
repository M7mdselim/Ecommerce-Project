package com.microservice.pro.order_service.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link IdempotentRequest}.
 */
@Repository
public interface IdempotentRequestRepository extends JpaRepository<IdempotentRequest, String> {

    /**
     * Looks up an existing idempotency key record.
     *
     * @param idempotencyKey the client-provided unique key
     * @return Optional containing the record if found, or empty
     */
    Optional<IdempotentRequest> findByIdempotencyKey(String idempotencyKey);
}
