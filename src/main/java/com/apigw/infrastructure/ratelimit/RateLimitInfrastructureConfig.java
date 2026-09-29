package com.apigw.infrastructure.ratelimit;

import com.apigw.application.ratelimit.RateLimitService;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.ratelimit.RateLimitRepository;
import com.apigw.proxy.ratelimit.RateLimitCatalog;
import com.apigw.proxy.ratelimit.RateLimitProperties;
import com.apigw.proxy.ratelimit.RateLimitWebFilter;
import com.apigw.proxy.ratelimit.RateLimiter;
import com.apigw.proxy.ratelimit.RedisRateLimitWindowStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * 限流功能装配。仅在 {@code apigw.rate-limit.enabled=true} 时整体生效：
 * 额度仓储（JDBC）、额度快照、Redis 窗口计数、限流器、管理应用服务与控制器、
 * 转发限流过滤器一起装上；关闭时本类不产生 bean。
 *
 * <b>限流与接入鉴权绑定开启</b>（{@code apigw.app-auth.enabled=true} 是前提）：
 * 限流维度是「接入应用」，应用编号必须来自接入鉴权过滤器改写后的可信 X-App-No，
 * 未开鉴权时这个头可被任意伪造，按它计数等于没有阀门。同时复用鉴权侧的
 * {@link AppCredentialRepository}（配额度时校验应用存在）与统一 {@link Clock}。
 *
 * 依赖口径：
 * - 额度配置存库（与接入凭据/访问流水共用共享数据源，开启限流即触发建池）；
 * - 窗口计数存 Redis（路由配置已在用的同一个 ReactiveStringRedisTemplate），
 *   多台网关共享同一份计数。
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(prefix = "apigw.rate-limit", name = "enabled", havingValue = "true")
public class RateLimitInfrastructureConfig {

    @Bean
    RateLimitRepository rateLimitRepository(JdbcTemplate gatewayJdbcTemplate,
                                            PlatformTransactionManager transactionManager) {
        return new JdbcRateLimitRepository(gatewayJdbcTemplate, transactionManager);
    }

    @Bean
    RateLimitCatalog rateLimitCatalog(RateLimitRepository rateLimitRepository) {
        return new RateLimitCatalog(rateLimitRepository);
    }

    @Bean
    RedisRateLimitWindowStore rateLimitWindowStore(ReactiveStringRedisTemplate redisTemplate,
                                                   RateLimitProperties properties) {
        return new RedisRateLimitWindowStore(redisTemplate, properties.windowSeconds());
    }

    @Bean
    RateLimiter rateLimiter(RateLimitCatalog rateLimitCatalog,
                            RedisRateLimitWindowStore windowStore,
                            RateLimitProperties properties) {
        return new RateLimiter(rateLimitCatalog, windowStore, properties);
    }

    @Bean
    RateLimitService rateLimitService(RateLimitRepository rateLimitRepository,
                                      AppCredentialRepository appCredentialRepository,
                                      ApplicationEventPublisher events,
                                      Clock gatewayClock) {
        return new RateLimitService(rateLimitRepository, appCredentialRepository, events, gatewayClock);
    }

    // RateLimitController 是 @RestController，由组件扫描注册（类上的 @ConditionalOnProperty
    // 在开关关闭时把它挡在容器外）。

    @Bean
    RateLimitWebFilter rateLimitWebFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        return new RateLimitWebFilter(rateLimiter, objectMapper);
    }
}
