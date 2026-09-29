package com.apigw.proxy.ratelimit;

import com.apigw.domain.ratelimit.RateLimitConfig;
import com.apigw.domain.ratelimit.RateLimitDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitWebFilterTest {

    private RateLimitConfigCatalog catalog;
    private RedisRateLimiter limiter;
    private DisposableServer server;
    private String baseUrl;
    private final AtomicInteger upstreamCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        catalog = mock(RateLimitConfigCatalog.class);
        limiter = mock(RedisRateLimiter.class);
        when(catalog.effective("app-1")).thenReturn(RateLimitConfig.of(2, 1));

        RateLimitWebFilter filter = new RateLimitWebFilter(catalog, limiter, new ObjectMapper());
        WebHandler tail = exchange -> {
            upstreamCalls.incrementAndGet();
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        };
        HttpWebHandlerAdapter adapter = new HttpWebHandlerAdapter(new FilteringWebHandler(tail, java.util.List.of(filter)));
        adapter.afterPropertiesSet();
        server = HttpServer.create().handle(new ReactorHttpHandlerAdapter((HttpHandler) adapter)).bindNow();
        baseUrl = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void rejectedRequestReturns429AndNeverReachesUpstream() {
        RateLimitDecision denied = RateLimitDecision.denied(
                RateLimitDecision.SCOPE_APP, 60_000L, 120_000L, 50_000L, 2L, null);
        when(limiter.checkAndAcquire(eq("app-1"), eq("127.0.0.1"), any()))
                .thenReturn(Mono.just(denied));

        var entity = WebClient.create()
                .get()
                .uri(baseUrl + "/orders/1")
                .header("X-App-No", "app-1")
                .exchangeToMono(resp -> resp.toBodilessEntity())
                .block(Duration.ofSeconds(5));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, entity.getStatusCode());
        assertEquals("50", entity.getHeaders().getFirst("Retry-After"));
        assertEquals(0, upstreamCalls.get());
        verify(limiter).checkAndAcquire(eq("app-1"), eq("127.0.0.1"), any());
    }

    @Test
    void allowedRequestReachesUpstream() {
        when(limiter.checkAndAcquire(eq("app-1"), any(), any()))
                .thenReturn(Mono.just(RateLimitDecision.allow(60_000L, 120_000L, 1L, 1L)));

        String body = WebClient.create().get().uri(baseUrl + "/orders/1")
                .header("X-App-No", "app-1")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(5));

        assertEquals("OK", body);
        assertEquals(1, upstreamCalls.get());
    }

    @Test
    void managementApiBypassesRateLimiter() {
        WebClient.create().get().uri(baseUrl + "/api/gateway/apps")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(5));

        assertEquals(1, upstreamCalls.get());
        verify(limiter, never()).checkAndAcquire(any(), any(), any());
        assertTrue(true);
    }
}
