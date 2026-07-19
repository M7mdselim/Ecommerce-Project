package com.microservice.pro.order_service.event;

import java.math.BigDecimal;

public record OrderCreatedEvent(String orderId, String customerId, BigDecimal amount) {}
