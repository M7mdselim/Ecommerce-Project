package com.microservice.pro.inventory_service.event;

public record PaymentFailedEvent(String orderId, String reason) {}
