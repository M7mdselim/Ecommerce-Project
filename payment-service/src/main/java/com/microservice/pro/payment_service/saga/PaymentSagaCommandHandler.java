package com.microservice.pro.payment_service.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.pro.payment_service.dto.PaymentRequest;
import com.microservice.pro.payment_service.dto.PaymentResponse;
import com.microservice.pro.payment_service.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class PaymentSagaCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(PaymentSagaCommandHandler.class);

    private final PaymentService paymentService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentSagaCommandHandler(PaymentService paymentService,
                                      KafkaTemplate<String, Object> kafkaTemplate,
                                      ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "saga-commands", groupId = "payment-saga-handler-group")
    public void handleCommand(String rawCommand) {
        logger.info("PAYMENT-HANDLER: Received saga command: {}", rawCommand);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cmd = objectMapper.readValue(rawCommand, Map.class);
            String commandType = String.valueOf(cmd.getOrDefault("commandType", ""));
            if (!"ProcessPaymentCommand".equals(commandType)) {
                return;
            }

            String orderId = (String) cmd.get("orderId");
            BigDecimal amount = new BigDecimal(cmd.get("amount").toString());

            try {
                PaymentResponse response = paymentService.processPayment(new PaymentRequest(orderId, amount));
                if ("SUCCESS".equals(response.status())) {
                    Map<String, Object> result = Map.of(
                            "type", "PaymentResultEvent",
                            "orderId", orderId,
                            "success", true,
                            "transactionId", response.transactionId() != null ? response.transactionId() : "TXN-OK"
                    );
                    kafkaTemplate.send("saga-results", orderId, result);
                } else {
                    Map<String, Object> result = Map.of(
                            "type", "PaymentResultEvent",
                            "orderId", orderId,
                            "success", false,
                            "reason", response.message() != null ? response.message() : "Payment rejected"
                    );
                    kafkaTemplate.send("saga-results", orderId, result);
                }
            } catch (Exception e) {
                logger.error("PAYMENT-HANDLER: Payment failed for order {}: {}", orderId, e.getMessage());
                Map<String, Object> result = Map.of(
                        "type", "PaymentResultEvent",
                        "orderId", orderId,
                        "success", false,
                        "reason", e.getMessage()
                );
                kafkaTemplate.send("saga-results", orderId, result);
            }
        } catch (Exception e) {
            logger.error("PAYMENT-HANDLER: Error processing command: {}", e.getMessage(), e);
        }
    }
}
