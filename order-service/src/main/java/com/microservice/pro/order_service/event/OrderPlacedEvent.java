package com.microservice.pro.order_service.event;

import java.math.BigDecimal;

public record OrderPlacedEvent(
        String orderId,
        String productId,
        int quantity,
        BigDecimal amount,
        String customerId
) {}
