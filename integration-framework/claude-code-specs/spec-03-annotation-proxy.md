# Spec 03 — Annotation Proxy (`@IntegrationClient` + `@Idempotent`)

## Context
This spec adds the declarative API surface on top of the `IntegrationExecutor` built in Spec 02.
Callers declare an interface; the framework generates a proxy that routes method calls through the
executor.

**Depends on**: spec-02-core-and-chain.md (`IntegrationExecutor`, `IntegrationRequest` exist).

---

## Package structure

```
framework/src/main/java/com/sura/integration/
├── annotation/
│   ├── IntegrationClient.java       # interface-level annotation
│   └── Idempotent.java              # method-level annotation
└── proxy/
    ├── IntegrationClientRegistrar.java   # ImportBeanDefinitionRegistrar
    ├── IntegrationClientFactoryBean.java # FactoryBean<T> per interface
    └── IntegrationClientInterceptor.java # InvocationHandler
```

---

## 1. `@IntegrationClient`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IntegrationClient {
    String name();       // logical target name, e.g. "payment-gateway"
    String baseUrl();    // base URL, e.g. "http://payment-service"
    String config();     // config key under integration.targets, e.g. "payment.gateway"
}
```

## 2. `@Idempotent`

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {
    /**
     * If true, the framework generates an Idempotency-Key when the caller does not supply one.
     * If false, no key is generated or propagated.
     * Default: true (auto-generate).
     */
    boolean autoGenerate() default true;
}
```

## 3. `IntegrationClientRegistrar.java`

Implements `ImportBeanDefinitionRegistrar`.

Scans the application packages for interfaces annotated with `@IntegrationClient`.
For each found interface, registers a `BeanDefinition` of type `IntegrationClientFactoryBean<T>`
with constructor arguments: `interfaceClass`, `IntegrationClient` annotation attributes.

Activated via `@EnableIntegrationClients` (see § 6) or Spring Boot autoconfiguration scan.

## 4. `IntegrationClientFactoryBean.java`

`FactoryBean<T>` where `T` is the annotated interface type.

Injected dependencies:
- `IntegrationProperties properties`
- `Map<String, IntegrationExecutor> executors` (from Spec 02 autoconfiguration)
- `IdempotencyStore idempotencyStore` (from Spec 04; inject as `@Autowired(required = false)` — graceful degradation if Redis not available)

`getObject()`:
- Reads `@IntegrationClient` metadata from the interface.
- Resolves `IntegrationExecutor` for `config()` key.
- Returns `Proxy.newProxyInstance(interfaceClassLoader, [interfaceClass], new IntegrationClientInterceptor(...))`.

## 5. `IntegrationClientInterceptor.java`

Implements `java.lang.reflect.InvocationHandler`.

Constructor fields:
- `IntegrationExecutor executor`
- `IdempotencyStore idempotencyStore` (nullable)
- `TargetProperties config`
- `String targetName`

### `invoke(Object proxy, Method method, Object[] args)` logic:

1. **Handle `Object` methods** (`equals`, `hashCode`, `toString`) — delegate directly.

2. **Extract HTTP metadata** from Spring Web annotations on the method:
   - `@GetExchange` → method = GET
   - `@PostExchange` → method = POST
   - `@PutExchange` → method = PUT
   - `@PatchExchange` → method = PATCH
   - Path from annotation value.

3. **Resolve `@RequestBody` argument** — find the parameter annotated with `@RequestBody`; that is the request body.

4. **Resolve idempotency**:
   - If method has `@Idempotent`:
     - `idempotent = true`
     - Check if any parameter is annotated `@RequestHeader("Idempotency-Key")` — use that value if present.
     - Else if `autoGenerate = true` and `IdempotencyStore` present: call `IdempotencyStore.generateKey()`.
     - Else: no key.
   - If method does NOT have `@Idempotent`: `idempotent = false`, no key.

5. **Check idempotency cache** (only if key is set):
   - Call `IdempotencyStore.get(key)`.
   - If hit: deserialize cached response and return immediately (no HTTP call).

6. **Build `IntegrationRequest`** with all resolved fields.

7. **Call `executor.execute(request)`** → `IntegrationResponse`.

8. **Store idempotency result** (only if key is set and request succeeded):
   - Call `IdempotencyStore.store(key, response, config.idempotency().ttl())`.

9. **Return** `response.body()` cast to method return type.

10. **On `IntegrationException`**: re-throw as-is (it is unchecked).

---

## 6. `@EnableIntegrationClients`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(IntegrationClientRegistrar.class)
public @interface EnableIntegrationClients {
    String[] basePackages() default {};
}
```

Consumers add `@EnableIntegrationClients(basePackages = "com.sura.demoservice")` on their
`@SpringBootApplication` class, or rely on autoconfiguration classpath scan.

---

## Usage example (for README)

```java
@IntegrationClient(
    name = "flaky-upstream",
    baseUrl = "http://localhost:8081",
    config = "flaky.upstream"
)
public interface FlakyUpstreamClient {

    @PostExchange("/work")
    @Idempotent
    WorkResponse doWork(@RequestBody WorkRequest request);
}
```

Consumer bean:

```java
@Service
public class OrderService {
    private final FlakyUpstreamClient client;

    public OrderService(FlakyUpstreamClient client) {
        this.client = client;
    }

    public WorkResponse process(WorkRequest req) {
        return client.doWork(req);  // all resilience + idempotency transparent
    }
}
```

---

## Acceptance criteria

- `FlakyUpstreamClient` interface (as above) is injected as a Spring bean without any explicit `@Bean` declaration.
- Method call on the proxy is routed through `IntegrationExecutor.execute()`.
- `@Idempotent` methods have `idempotent = true` set on `IntegrationRequest`; non-annotated methods have `idempotent = false`.
- If `IdempotencyStore` has a cached entry for the key, the HTTP call is skipped entirely.
- `Object` methods (`toString`, `hashCode`, `equals`) do not route through the executor.
- Proxy works with any interface type (not bound to a specific return type).
