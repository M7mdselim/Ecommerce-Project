package com.microservice.pro.order_service.event;

public record OrderCancelledEvent(String orderId, String reason) {}
