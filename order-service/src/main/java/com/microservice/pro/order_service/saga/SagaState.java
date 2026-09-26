package com.microservice.pro.order_service.saga;

public enum SagaState {
    STARTED,
    INVENTORY_RESERVING,
    INVENTORY_RESERVED,
    PAYMENT_PROCESSING,
    COMPLETED,
    INVENTORY_FAILED,
    PAYMENT_FAILED,
    COMPENSATING,
    CANCELLED
}
