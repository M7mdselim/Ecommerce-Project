package com.microservice.pro.order_service.event;

public record InventoryReservedEvent(String orderId, String productId, int quantity) {}
