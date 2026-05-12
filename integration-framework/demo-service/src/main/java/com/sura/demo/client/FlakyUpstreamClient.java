package com.sura.demo.client;

import com.sura.demo.model.WorkRequest;
import com.sura.demo.model.WorkResponse;
import com.sura.integration.annotation.Idempotent;
import com.sura.integration.annotation.IntegrationClient;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

@IntegrationClient(
        name    = "flaky-upstream",
        baseUrl = "${integration.targets.flaky-upstream.base-url}",
        config  = "flaky-upstream"
)
public interface FlakyUpstreamClient {

    @PostExchange("/work")
    @Idempotent
    WorkResponse doWork(@RequestBody WorkRequest request);
}
