package com.apigw.application.ratelimit;

import com.apigw.common.exception.BizException;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.ratelimit.RateLimitConfig;
import com.apigw.infrastructure.ratelimit.RateLimitConfigStore;
import com.apigw.proxy.ratelimit.RateLimitConfigCatalog;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 限流额度管理用例。
 *
 * Redis 是配置权威来源；写成功后立刻刷新本实例热快照，并通过 Redis Pub/Sub 通知其它实例。
 * 查询「生效值」时回源 Redis 读取当前配置，再按应用配置 → 默认配置 → 不限的顺序按层合并。
 */
public class RateLimitAdminService {

    private final RateLimitConfigStore store;
    private final AppCredentialRepository appRepository;
    private final RateLimitConfigCatalog catalog;

    public RateLimitAdminService(RateLimitConfigStore store,
                                 AppCredentialRepository appRepository,
                                 RateLimitConfigCatalog catalog) {
        this.store = store;
        this.appRepository = appRepository;
        this.catalog = catalog;
    }

    public Mono<RateLimitView> getDefault() {
        return store.loadAll().map(all -> {
            RateLimitConfig configured = all.getOrDefault(
                    RateLimitConfigStore.DEFAULT_APP_NO, RateLimitConfig.of(null, null));
            return new RateLimitView(RateLimitConfigStore.DEFAULT_APP_NO, configured, configured);
        });
    }

    public Mono<RateLimitView> setDefault(RateLimitConfig requested) {
        return store.saveAndPublish(RateLimitConfigStore.DEFAULT_APP_NO, requested)
                .then(store.loadAll())
                .flatMap(this::refreshAndGetDefault);
    }

    public Mono<Void> clearDefault() {
        return store.deleteAndPublish(RateLimitConfigStore.DEFAULT_APP_NO)
                .then(catalog.refreshAfterLocalChange()).then();
    }

    public Mono<RateLimitView> getApp(String appNo) {
        return assertAppExists(appNo)
                .then(store.loadAll())
                .map(all -> viewForApp(appNo, all));
    }

    public Mono<RateLimitView> setApp(String appNo, RateLimitConfig requested) {
        return assertAppExists(appNo)
                .then(store.saveAndPublish(appNo, requested))
                .then(store.loadAll())
                .flatMap(all -> refreshAndGetApp(appNo, all));
    }

    public Mono<Void> clearApp(String appNo) {
        return assertAppExists(appNo)
                .then(store.deleteAndPublish(appNo))
                .then(catalog.refreshAfterLocalChange())
                .then();
    }

    private Mono<RateLimitView> refreshAndGetDefault(Map<String, RateLimitConfig> all) {
        return catalog.refreshAfterLocalChange()
                .thenReturn(viewForApp(RateLimitConfigStore.DEFAULT_APP_NO, all));
    }

    private Mono<RateLimitView> refreshAndGetApp(String appNo, Map<String, RateLimitConfig> all) {
        return catalog.refreshAfterLocalChange()
                .thenReturn(viewForApp(appNo, all));
    }

    private RateLimitView viewForApp(String appNo, Map<String, RateLimitConfig> all) {
        boolean isDefault = RateLimitConfigStore.DEFAULT_APP_NO.equals(appNo);
        RateLimitConfig defaults = all.getOrDefault(
                RateLimitConfigStore.DEFAULT_APP_NO, RateLimitConfig.of(null, null));
        RateLimitConfig configured = isDefault
                ? defaults
                : all.getOrDefault(appNo, RateLimitConfig.of(null, null));
        RateLimitConfig effective = isDefault ? defaults : merge(configured, defaults);
        return new RateLimitView(appNo, configured, effective);
    }

    private static RateLimitConfig merge(RateLimitConfig app, RateLimitConfig defaults) {
        return RateLimitConfig.of(
                app.appLimit() != null ? app.appLimit() : defaults.appLimit(),
                app.sourceLimit() != null ? app.sourceLimit() : defaults.sourceLimit());
    }

    private Mono<Void> assertAppExists(String appNo) {
        return Mono.fromCallable(() -> appRepository.findByAppNo(appNo)
                        .orElseThrow(() -> new BizException(404, "应用不存在：" + appNo)))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * @param configured 该应用自定义值；null 字段表示未覆盖，默认额度的空字段表示未配置
     * @param effective  按层合并后的当前生效值；null 表示该层不限，-1 表示显式不限
     */
    public record RateLimitView(String appNo, RateLimitConfig configured, RateLimitConfig effective) {
    }
}
