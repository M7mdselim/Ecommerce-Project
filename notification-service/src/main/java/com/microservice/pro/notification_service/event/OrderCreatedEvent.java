package com.microservice.pro.notification_service.event;

import java.math.BigDecimal;

public record OrderCreatedEvent(
        String orderId,
        String productId,
        int quantity,
        BigDecimal amount
) {}
