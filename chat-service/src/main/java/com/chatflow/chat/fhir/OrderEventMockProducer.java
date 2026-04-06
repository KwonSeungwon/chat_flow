package com.chatflow.chat.fhir;

import com.chatflow.common.dto.ApiResponse;
import com.chatflow.common.dto.OrderEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/fhir/mock")
@RequiredArgsConstructor
public class OrderEventMockProducer {

    private static final String ORDER_EVENTS_TOPIC = "order-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @PostMapping("/order")
    public ResponseEntity<ApiResponse<OrderEvent>> publishOrderEvent(@RequestBody OrderEvent event) {
        if (event.getOrderId() == null) {
            event.setOrderId(UUID.randomUUID().toString());
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(LocalDateTime.now());
        }
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(ORDER_EVENTS_TOPIC, event.getRoomId(), payload);
            log.info("OrderEvent 발행: orderId={}, roomId={}", event.getOrderId(), event.getRoomId());
            return ResponseEntity.ok(ApiResponse.ok(event, "처방 이벤트가 발행되었습니다."));
        } catch (JsonProcessingException e) {
            log.error("OrderEvent 직렬화 실패", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("이벤트 직렬화 실패: " + e.getMessage()));
        }
    }
}
