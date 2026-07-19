package com.microservice.pro.order_service.event;

public record InventoryReservationFailedEvent(String orderId, String reason) {}
