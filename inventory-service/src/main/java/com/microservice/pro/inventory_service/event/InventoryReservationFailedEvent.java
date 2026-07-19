package com.microservice.pro.inventory_service.event;

public record InventoryReservationFailedEvent(String orderId, String reason) {}
