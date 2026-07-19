package com.microservice.pro.payment_service.event;

public record InventoryReservedEvent(String orderId, String productId, int quantity) {}
