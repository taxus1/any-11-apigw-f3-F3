package com.apigw.proxy.ratelimit;

import com.apigw.common.web.ClientIpResolver;
import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.ratelimit.RateLimitDecision;
import com.apigw.proxy.error.GatewayErrors;
import com.apigw.proxy.error.UpstreamFailureKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 接入应用两层限流过滤器：应用总量 + 应用下单来源地址。
 *
 * 顺序在接入鉴权之后、转发过滤器之前：只有鉴权通过并被网关重写过的可信应用编号才参与限流；
 * 限流拒绝在这里短路，请求不会匹配路由，也不会打到上游。
 */
@Slf4j
public class RateLimitWebFilter implements WebFilter, Ordered {

    /** 接入鉴权 +5；先认人，再限流；转发 +10。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 7;

    private final RateLimitConfigCatalog catalog;
    private final RedisRateLimiter limiter;
    private final ObjectMapper objectMapper;

    public RateLimitWebFilter(RateLimitConfigCatalog catalog,
                              RedisRateLimiter limiter,
                              ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (isPassthrough(path)) {
            return chain.filter(exchange);
        }

        // 该头已由 AppAuthWebFilter 在认证通过后覆盖；限流过滤器只在应用鉴权启用时装配，
        // 因此这里读到的不是调用方可任意伪造的原始值。
        String appNo = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.APP_NO_HEADER);
        if (appNo == null || appNo.isBlank()) {
            return chain.filter(exchange);
        }

        String resolvedIp = ClientIpResolver.resolve(exchange.getRequest());
        String normalizedIp = ClientIpResolver.canonicalize(resolvedIp);
        final String canonicalIp = normalizedIp == null ? "unknown" : normalizedIp;

        return limiter.checkAndAcquire(appNo, canonicalIp, catalog.effective(appNo))
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        return chain.filter(exchange);
                    }
                    return reject(exchange, appNo, canonicalIp, decision);
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange, String appNo, String sourceIp,
                              RateLimitDecision decision) {
        String traceId = resolveTraceId(exchange);
        long retryAfterSeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
        log.warn("限流拒绝 scope={} appNo={} sourceIp={} retryAfter={}s traceId={}",
                decision.scope(), appNo, sourceIp, retryAfterSeconds, traceId);
        return GatewayErrors.write(exchange, objectMapper, UpstreamFailureKind.RATE_LIMITED,
                traceId, null,
                headers -> addRateLimitHeaders(headers, decision, retryAfterSeconds),
                body -> {
                    body.put("retryAfterSeconds", retryAfterSeconds);
                    body.put("windowStartMs", decision.windowStartMs());
                    body.put("windowResetMs", decision.windowEndMs());
                });
    }

    private void addRateLimitHeaders(HttpHeaders headers, RateLimitDecision decision,
                                     long retryAfterSeconds) {
        // Retry-After 单位是秒，语义不是随机退避，而是“距离当前固定分钟窗口结束还要等多久”。
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        headers.set("X-RateLimit-Window-Start-Ms", Long.toString(decision.windowStartMs()));
        headers.set("X-RateLimit-Window-Reset-Ms", Long.toString(decision.windowEndMs()));
    }

    private String resolveTraceId(ServerWebExchange exchange) {
        String incoming = GatewayHeaders.normalizeTraceId(
                exchange.getRequest().getHeaders().getFirst(GatewayHeaders.TRACE_ID_HEADER));
        return incoming != null ? incoming : GatewayHeaders.newRequestId();
    }

    private boolean isPassthrough(String path) {
        return path.equals("/api") || path.startsWith("/api/")
                || path.equals("/actuator") || path.startsWith("/actuator/");
    }
}
