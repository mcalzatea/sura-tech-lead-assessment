package com.sura.demo.service;

import com.sura.demo.client.FlakyUpstreamClient;
import com.sura.demo.model.OrderRequest;
import com.sura.demo.model.OrderResponse;
import com.sura.demo.model.WorkRequest;
import com.sura.demo.model.WorkResponse;
import com.sura.integration.model.IntegrationException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrderService {

    private final FlakyUpstreamClient flakyUpstreamClient;

    public OrderService(FlakyUpstreamClient flakyUpstreamClient) {
        this.flakyUpstreamClient = flakyUpstreamClient;
    }

    public OrderResponse process(OrderRequest req) {
        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        try {
            WorkResponse work = flakyUpstreamClient.doWork(
                    new WorkRequest(req.sku(), req.qty(), correlationId));

            return new OrderResponse(
                    UUID.randomUUID().toString(),
                    "ACCEPTED",
                    1,   // framework logs actual attempt count; proxy returns body only
                    System.currentTimeMillis() - start,
                    currentTraceId());

        } catch (IntegrationException e) {
            return new OrderResponse(
                    null,
                    "FAILED",
                    e.getAttemptCount(),
                    System.currentTimeMillis() - start,
                    e.getTraceId());
        }
    }

    private String currentTraceId() {
        SpanContext ctx = Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : "none";
    }
}
