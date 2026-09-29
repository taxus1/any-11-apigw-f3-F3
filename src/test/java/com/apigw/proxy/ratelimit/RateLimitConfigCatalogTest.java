package com.apigw.proxy.ratelimit;

import com.apigw.domain.ratelimit.RateLimitConfig;
import com.apigw.infrastructure.ratelimit.RateLimitConfigStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitConfigCatalogTest {

    private final RateLimitConfigStore store = mock(RateLimitConfigStore.class);
    private final RateLimitProperties properties = new RateLimitProperties(
            true, Duration.ofSeconds(10), Duration.ofMillis(100), Duration.ofSeconds(5));
    private final RateLimitConfigCatalog catalog =
            new RateLimitConfigCatalog(store, null, properties);

    @Test
    void effectiveConfigFallsBackLayerByLayer() {
        when(store.loadAll()).thenReturn(reactor.core.publisher.Mono.just(Map.of(
                RateLimitConfigStore.DEFAULT_APP_NO, RateLimitConfig.of(1000, 100),
                "app-a", RateLimitConfig.of(300, null),
                "app-b", RateLimitConfig.of(null, -1),
                "app-c", RateLimitConfig.of(-1, 20))));

        catalog.refresh().block();

        assertEquals(RateLimitConfig.of(1000, 100), catalog.effective("unconfigured"));
        assertEquals(RateLimitConfig.of(300, 100), catalog.effective("app-a"));
        assertEquals(RateLimitConfig.of(1000, -1), catalog.effective("app-b"));
        assertEquals(RateLimitConfig.of(-1, 20), catalog.effective("app-c"));
    }

    @Test
    void noConfigMeansUnlimited() {
        when(store.loadAll()).thenReturn(reactor.core.publisher.Mono.just(Map.of()));

        catalog.refresh().block();

        assertEquals(RateLimitConfig.of(null, null), catalog.effective("new-app"));
    }
}
