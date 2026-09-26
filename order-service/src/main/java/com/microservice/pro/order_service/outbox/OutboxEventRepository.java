package com.microservice.pro.order_service.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for the {@link OutboxEvent} table.
 *
 * <p>The relay ({@link OutboxEventRelay}) uses {@link #findPendingEvents(int)}
 * to fetch a bounded batch of unpublished events each tick, limiting memory
 * pressure and preventing a slow Kafka from backing up unboundedly.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * Fetches up to {@code limit} PENDING outbox events ordered by creation time
     * (oldest-first) so events are published in the order they were written.
     *
     * <p>LIMIT in JPQL requires a {@code Pageable} parameter. We use a native
     * query here for simplicity — it works with both H2 (dev) and PostgreSQL (prod).
     *
     * @param limit maximum number of rows to fetch per relay poll cycle
     * @return list of pending events, oldest first
     */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit",
           nativeQuery = true)
    List<OutboxEvent> findPendingEvents(int limit);
}
