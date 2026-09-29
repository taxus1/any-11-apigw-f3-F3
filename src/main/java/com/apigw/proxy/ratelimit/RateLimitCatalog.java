package com.apigw.proxy.ratelimit;

import com.apigw.domain.ratelimit.RateLimitRepository;
import com.apigw.domain.ratelimit.RateLimitScope;
import com.apigw.domain.ratelimit.RateQuota;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * 转发限流侧的额度内存快照。和 {@code AppCredentialCatalog}/{@code RouteCatalog} 同一套路：
 *
 * 新鲜度：
 * 1. 管理接口改完额度（事务提交后）发 {@link RateLimitChangedEvent}，收到事件立即重载——
 *    <b>额度一改，之后的新请求就按新额度判定</b>，不等重启；
 * 2. {@link #scheduledRefresh()} 按 {@code apigw.rate-limit.refresh-interval}（默认 10s）兜底，
 *    多实例部署时别的实例改了额度，本实例在一个周期内收敛。
 *
 * 判定回落顺序（说得清每笔请求到底按哪个值在限）：
 * - 应用层：应用专属 APP 行；没有则 DEFAULT 行；都没有（或额度为 null）= 不限；
 * - 来源层：该应用 + 该来源的 IP 行；没有 IP 行 = 这一层不限（IP 层<b>不</b>回落默认，
 *   默认额度只回答「这个应用总量多少」，来源层没配就是只卡总量、不单独卡地址）。
 *
 * 容错：重载失败时沿用旧快照（运营改的额度最坏一个刷新周期后补收敛）；额度快照缺失
 * （从没加载成功）时 resolve 返回「两层都不限」——配合计数存储故障的同一可用性取向：
 * 配置面的一时抖动不该把全部调用方挡在门外（此事会打 warn 并有指标）。
 *
 * JDBC 是阻塞调用，所有读库统一切到 boundedElastic，绝不占 Netty 事件循环。
 */
@Slf4j
public class RateLimitCatalog {

    private final RateLimitRepository repository;
    private volatile Snapshot snapshot;

    public RateLimitCatalog(RateLimitRepository repository) {
        this.repository = repository;
    }

    /**
     * 当次请求生效的两层额度。值为 null = 该层不限。
     * 应用层已按 APP → DEFAULT → 不限 回落好；来源层只看 IP 行，没配即 null。
     */
    public EffectiveQuota resolve(String appNo, String canonicalIp) {
        Snapshot current = this.snapshot;
        if (current == null) {
            return EffectiveQuota.unlimited();
        }
        Integer appLimit;
        if (current.appLimits.containsKey(appNo)) {
            // 应用专属行存在：哪怕值是 null（显式不限）也以它为准，不回落默认
            appLimit = current.appLimits.get(appNo);
        } else {
            // 没给这个应用配：按默认；默认也没配/默认配的是 null = 不限
            appLimit = current.defaultLimit;
        }
        Integer ipLimit = null;
        if (canonicalIp != null) {
            Map<String, Integer> perApp = current.ipLimits.get(appNo);
            if (perApp != null && perApp.containsKey(canonicalIp)) {
                // IP 行存在：null 表示这个地址显式不限（只受应用总量约束）
                ipLimit = perApp.get(canonicalIp);
            }
        }
        return new EffectiveQuota(appLimit, ipLimit);
    }

    /** 管理查询用：说清每个生效值来自应用专属配置、默认额度还是「不限」。 */
    public EffectiveQuotaView describe(String appNo, String canonicalIp) {
        Snapshot current = this.snapshot;
        if (current == null) {
            return new EffectiveQuotaView(null, "UNLIMITED", null, "UNLIMITED");
        }
        Integer appEffective;
        String appSource;
        if (current.appLimits.containsKey(appNo)) {
            // 应用专属行存在：null=对该应用显式不限（也不回落默认）
            appEffective = current.appLimits.get(appNo);
            appSource = "APP";
        } else if (current.defaultLimit != null) {
            appEffective = current.defaultLimit;
            appSource = "DEFAULT";
        } else {
            appEffective = null;
            appSource = "UNLIMITED";
        }
        Integer ipEffective = null;
        String ipSource = "UNLIMITED";
        if (canonicalIp != null) {
            Map<String, Integer> perApp = current.ipLimits.get(appNo);
            if (perApp != null && perApp.containsKey(canonicalIp)) {
                ipEffective = perApp.get(canonicalIp);
                ipSource = "IP";
            }
        }
        return new EffectiveQuotaView(appEffective, appSource, ipEffective, ipSource);
    }

    /** 变更事件：立即重载，额度对下一笔新请求即时生效。 */
    @EventListener(RateLimitChangedEvent.class)
    public void onChanged(RateLimitChangedEvent event) {
        log.debug("收到限流额度变更事件（{} {}/{}），立即重载额度快照", event.scope(), event.appNo(), event.ip());
        refresh().subscribe(
                n -> log.info("限流额度快照已按变更事件重载，当前额度行 {} 条", n),
                err -> log.warn("限流额度快照变更后重载失败，继续沿用上一份：{}", err.toString()));
    }

    /** 兜底轮询：多实例间最终一致。 */
    @Scheduled(fixedDelayString = "${apigw.rate-limit.refresh-interval-ms:10000}")
    public void scheduledRefresh() {
        refresh().subscribe(
                n -> log.debug("限流额度快照定时刷新完成，当前额度行 {} 条", n),
                err -> log.debug("限流额度快照定时刷新失败，沿用上一份：{}", err.toString()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        refresh().subscribe(
                n -> log.info("限流额度快照预热完成，当前额度行 {} 条", n),
                err -> log.warn("限流额度快照预热失败（库未就绪？），稍后定时重试：{}", err.toString()));
    }

    /** 拉一份新快照；失败保留旧快照（从没成功过时 snapshot=null，resolve 退化为不限）。 */
    public Mono<Integer> refresh() {
        return Mono.fromCallable(repository::loadAll)
                .subscribeOn(Schedulers.boundedElastic())
                .map(rows -> buildSnapshot(rows))
                .onErrorResume(err -> {
                    log.warn("加载限流额度快照失败：{}", err.toString());
                    return Mono.just(this.snapshot == null ? 0 : this.snapshot.rowCount);
                });
    }

    public boolean hasSnapshot() {
        return snapshot != null;
    }

    /** 测试/启动期需要同步拿到首份快照时用。 */
    public void refreshBlock(java.time.Duration timeout) {
        refresh().block(timeout);
    }

    private int buildSnapshot(List<RateQuota> rows) {
        Integer defaultLimit = null;
        Map<String, Integer> appLimits = new HashMap<>();
        Map<String, Map<String, Integer>> ipLimits = new HashMap<>();
        for (RateQuota row : rows) {
            if (row.scope() == RateLimitScope.DEFAULT) {
                defaultLimit = row.perMinuteLimit();
            } else if (row.scope() == RateLimitScope.APP) {
                // 允许 null（显式不限），所以不能用 Map.copyOf（它禁 null）
                appLimits.put(row.appNo(), row.perMinuteLimit());
            } else if (row.scope() == RateLimitScope.IP) {
                ipLimits.computeIfAbsent(row.appNo(), k -> new HashMap<>())
                        .put(row.ip(), row.perMinuteLimit());
            }
        }
        Snapshot built = new Snapshot(defaultLimit, immutable(appLimits), immutableNested(ipLimits), rows.size());
        this.snapshot = built;
        return rows.size();
    }    /** null 值也要保留（显式不限），故不用 Map.copyOf；包成不可修改视图防止外部改动快照。 */
    private static Map<String, Integer> immutable(Map<String, Integer> src) {
        return Collections.unmodifiableMap(new HashMap<>(src));
    }

    private static Map<String, Map<String, Integer>> immutableNested(Map<String, Map<String, Integer>> src) {
        Map<String, Map<String, Integer>> copy = new HashMap<>(src.size() * 2);
        for (Map.Entry<String, Map<String, Integer>> e : src.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableMap(new HashMap<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 当次请求的两层生效额度；任一为 null = 该层不限。 */
    public record EffectiveQuota(Integer appPerMinute, Integer ipPerMinute) {

        static EffectiveQuota unlimited() {
            return new EffectiveQuota(null, null);
        }

        public boolean nothingLimited() {
            return appPerMinute == null && ipPerMinute == null;
        }
    }

    /** 管理查询视图：生效值 + 来源口径（APP/DEFAULT/IP/UNLIMITED）。 */
    public record EffectiveQuotaView(Integer appPerMinute, String appSource,
                                     Integer ipPerMinute, String ipSource) {
    }

    private record Snapshot(Integer defaultLimit,
                            Map<String, Integer> appLimits,
                            Map<String, Map<String, Integer>> ipLimits,
                            int rowCount) {
    }
}
