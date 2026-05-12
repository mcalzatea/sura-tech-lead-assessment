package com.sura.integration.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sura.integration.annotation.Idempotent;
import com.sura.integration.config.TargetProperties;
import com.sura.integration.core.IntegrationExecutor;
import com.sura.integration.idempotency.CachedResponse;
import com.sura.integration.idempotency.IdempotencyStore;
import com.sura.integration.model.IntegrationRequest;
import com.sura.integration.model.IntegrationResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public class IntegrationClientInterceptor implements InvocationHandler {

    private final IntegrationExecutor executor;
    private final IdempotencyStore    store;        // may be null (no Redis)
    private final ObjectMapper        objectMapper; // may be null (no Redis)
    private final TargetProperties    config;
    private final String              targetName;

    public IntegrationClientInterceptor(IntegrationExecutor executor,
                                        IdempotencyStore store,
                                        ObjectMapper objectMapper,
                                        TargetProperties config,
                                        String targetName) {
        this.executor     = executor;
        this.store        = store;
        this.objectMapper = objectMapper;
        this.config       = config;
        this.targetName   = targetName;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        // 1. Delegate Object methods directly to avoid routing through the executor
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // 2. Resolve HTTP method and path from Spring @*Exchange annotations
        HttpMethod httpMethod;
        String path;
        if (method.isAnnotationPresent(GetExchange.class)) {
            httpMethod = HttpMethod.GET;
            path       = method.getAnnotation(GetExchange.class).value();
        } else if (method.isAnnotationPresent(PostExchange.class)) {
            httpMethod = HttpMethod.POST;
            path       = method.getAnnotation(PostExchange.class).value();
        } else if (method.isAnnotationPresent(PutExchange.class)) {
            httpMethod = HttpMethod.PUT;
            path       = method.getAnnotation(PutExchange.class).value();
        } else if (method.isAnnotationPresent(PatchExchange.class)) {
            httpMethod = HttpMethod.PATCH;
            path       = method.getAnnotation(PatchExchange.class).value();
        } else {
            throw new IllegalStateException(
                    "Method '" + method.getName() + "' on " + targetName
                    + " has no @GetExchange/@PostExchange/@PutExchange/@PatchExchange annotation");
        }

        // 3. Find @RequestBody argument
        Object body = null;
        Parameter[] params = method.getParameters();
        Object[] safeArgs = args != null ? args : new Object[0];
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAnnotationPresent(RequestBody.class)) {
                body = safeArgs[i];
                break;
            }
        }

        // 4. Resolve idempotency
        boolean idempotent     = false;
        String  idempotencyKey = null;
        Idempotent idempotentAnn = method.getAnnotation(Idempotent.class);

        if (idempotentAnn != null) {
            idempotent = true;
            String headerName = config.getIdempotency() != null
                    ? config.getIdempotency().getHeaderName()
                    : "Idempotency-Key";

            // Prefer caller-supplied key via @RequestHeader
            for (int i = 0; i < params.length; i++) {
                RequestHeader rh = params[i].getAnnotation(RequestHeader.class);
                if (rh != null) {
                    String name = rh.value().isEmpty() ? rh.name() : rh.value();
                    if (headerName.equals(name) && safeArgs[i] instanceof String s) {
                        idempotencyKey = s;
                        break;
                    }
                }
            }

            // Auto-generate when no caller-supplied key
            if (idempotencyKey == null && idempotentAnn.autoGenerate() && store != null) {
                idempotencyKey = store.generateKey();
            }
        }

        // 5. Short-circuit on idempotency cache hit — deserialize bodyJson to method return type
        if (idempotencyKey != null && store != null) {
            Optional<CachedResponse> cached = store.get(idempotencyKey);
            if (cached.isPresent()) {
                if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
                    return null;
                }
                return objectMapper.readValue(cached.get().bodyJson(), method.getReturnType());
            }
        }

        // 6. Build and execute the request
        IntegrationRequest request = new IntegrationRequest(
                httpMethod.name(), path, body, Map.of(),
                idempotent, idempotencyKey, method.getReturnType());

        IntegrationResponse response = executor.execute(request);

        // 7. Persist idempotency result for future deduplication
        if (idempotencyKey != null && store != null) {
            Duration ttl = config.getIdempotency() != null
                    ? config.getIdempotency().getTtl()
                    : Duration.ofHours(24);
            store.store(idempotencyKey, response, ttl);
        }

        // 8. Return body (null for void methods)
        if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
            return null;
        }
        return response.body();
    }
}
