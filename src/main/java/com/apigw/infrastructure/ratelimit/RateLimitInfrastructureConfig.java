package com.apigw.infrastructure.ratelimit;

import com.apigw.application.ratelimit.RateLimitAdminService;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.proxy.ratelimit.RateLimitConfigCatalog;
import com.apigw.proxy.ratelimit.RateLimitProperties;
import com.apigw.proxy.ratelimit.RateLimitWebFilter;
import com.apigw.proxy.ratelimit.RedisRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * 接入应用限流装配。
 *
 * 限流只处理已经通过接入鉴权的可信应用编号，因此必须在
 * {@code apigw.app-auth.enabled=true} 时才启用；默认随应用鉴权打开。
 * 如需保留接入鉴权但临时关闭限流，可设置 {@code apigw.rate-limit.enabled=false}。
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnExpression("${apigw.rate-limit.enabled:true} and ${apigw.app-auth.enabled:false}")
public class RateLimitInfrastructureConfig {

    @Bean
    RateLimitConfigStore rateLimitConfigStore(ReactiveStringRedisTemplate redis,
                                              ObjectMapper objectMapper) {
        return new RedisRateLimitConfigStore(redis, objectMapper);
    }

    @Bean
    RateLimitConfigCatalog rateLimitConfigCatalog(RateLimitConfigStore store,
                                                  ReactiveStringRedisTemplate redis,
                                                  RateLimitProperties properties) {
        return new RateLimitConfigCatalog(store, redis, properties);
    }

    @Bean(name = "appRedisRateLimiter")
    RedisRateLimiter appRedisRateLimiter(ReactiveStringRedisTemplate redis,
                                         RateLimitProperties properties) {
        return new RedisRateLimiter(redis, properties);
    }

    @Bean
    RateLimitWebFilter rateLimitWebFilter(RateLimitConfigCatalog catalog,
                                          RedisRateLimiter limiter,
                                          ObjectMapper objectMapper) {
        return new RateLimitWebFilter(catalog, limiter, objectMapper);
    }

    @Bean
    RateLimitAdminService rateLimitAdminService(RateLimitConfigStore store,
                                                AppCredentialRepository appRepository,
                                                RateLimitConfigCatalog catalog) {
        return new RateLimitAdminService(store, appRepository, catalog);
    }
}
