package com.sura.demo.controller;

import com.sura.demo.model.OrderRequest;
import com.sura.demo.model.OrderResponse;
import com.sura.demo.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        OrderResponse response = orderService.process(request);
        int status = "ACCEPTED".equals(response.status()) ? 200 : 502;
        return ResponseEntity.status(status).body(response);
    }
}
