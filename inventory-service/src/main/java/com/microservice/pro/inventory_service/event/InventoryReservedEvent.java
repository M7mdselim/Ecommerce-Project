package com.microservice.pro.inventory_service.event;

public record InventoryReservedEvent(String orderId, String productId, int quantity) {}
