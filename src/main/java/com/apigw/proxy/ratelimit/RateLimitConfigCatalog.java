package com.apigw.proxy.ratelimit;

import com.apigw.domain.ratelimit.RateLimitConfig;
import com.apigw.infrastructure.ratelimit.RateLimitConfigStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 限流配置的热快照。
 *
 * 权威配置在 Redis；请求路径只读本地 volatile Map，不每笔查 Redis。配置保存后：
 * 1. 本实例马上刷新；
 * 2. Redis Lua 原子 PUBLISH，其他实例收到后立即刷新；
 * 3. 消息丢失/订阅重连期间由定时刷新在一个周期内兜底。
 *
 * 生效口径按层合并：应用配置的某一层为 null 时继承默认配置的同一层；仍没有配置则为 null，
 * 运行时把 null 解释成「不限」。应用配置可用 -1 显式覆盖默认值，表示该层不限。
 */
@Slf4j
public class RateLimitConfigCatalog {

    private final RateLimitConfigStore store;
    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    private volatile Map<String, RateLimitConfig> snapshot = Map.of();

    public RateLimitConfigCatalog(RateLimitConfigStore store,
                                  ReactiveStringRedisTemplate redis,
                                  RateLimitProperties properties) {
        this.store = store;
        this.redis = redis;
        this.properties = properties;
    }

    /** 查询当前生效配置（按层回退默认）。 */
    public RateLimitConfig effective(String appNo) {
        Map<String, RateLimitConfig> current = snapshot;
        RateLimitConfig app = current.get(appNo);
        RateLimitConfig defaults = current.get(RateLimitConfigStore.DEFAULT_APP_NO);
        Integer appLimit = pick(app == null ? null : app.appLimit(),
                defaults == null ? null : defaults.appLimit());
        Integer sourceLimit = pick(app == null ? null : app.sourceLimit(),
                defaults == null ? null : defaults.sourceLimit());
        return RateLimitConfig.of(appLimit, sourceLimit);
    }

    private static Integer pick(Integer appValue, Integer defaultValue) {
        return appValue != null ? appValue : defaultValue;
    }

    public Mono<Integer> refresh() {
        return store.loadAll()
                .map(loaded -> {
                    snapshot = Map.copyOf(loaded);
                    return loaded.size();
                })
                .onErrorResume(err -> {
                    log.warn("刷新加限流配置失败，继续沿用上一份：{}", err.toString());
                    return Mono.just(snapshot.size());
                });
    }

    /** 本实例管理操作完成后调用：不等定时任务，之后的新请求立即按新配置。 */
    public Mono<Integer> refreshAfterLocalChange() {
        return refresh();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        refresh().subscribe(
                n -> log.info("限流配置预热完成，配置项 {} 个", n),
                err -> log.warn("限流配置预热失败：{}", err.toString()));
        subscribeChanges();
    }

    @Scheduled(fixedDelayString = "${apigw.rate-limit.refresh-interval-ms:10000}")
    public void scheduledRefresh() {
        refresh().subscribe(
                n -> log.debug("限流配置定时刷新完成，配置项 {} 个", n),
                err -> log.debug("限流配置定时刷新失败：{}", err.toString()));
    }

    private void subscribeChanges() {
        if (!subscribed.compareAndSet(false, true)) {
            return;
        }
        reactor.core.publisher.Flux.defer(() -> redis.listenTo(ChannelTopic.of(
                        com.apigw.infrastructure.ratelimit.RedisRateLimitConfigStore.EVENT_CHANNEL)))
                .map(ReactiveSubscription.Message::getMessage)
                .onBackpressureLatest()
                .concatMap(message -> {
                    log.debug("收到限流配置变更通知 {}", message);
                    return refresh().then(Mono.empty());
                })
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        ignored -> {
                        },
                        err -> {
                            subscribed.set(false);
                            log.warn("限流配置变更订阅中断，等待定时刷新兜底：{}", err.toString());
                        });
    }

    Map<String, RateLimitConfig> snapshotForTest() {
        return snapshot;
    }
}
