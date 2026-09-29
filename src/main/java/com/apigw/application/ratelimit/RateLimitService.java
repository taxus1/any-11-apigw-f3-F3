package com.apigw.application.ratelimit;

import com.apigw.common.exception.BizException;
import com.apigw.common.web.ClientIpResolver;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.ratelimit.RateLimitRepository;
import com.apigw.domain.ratelimit.RateLimitScope;
import com.apigw.domain.ratelimit.RateQuota;
import com.apigw.proxy.ratelimit.RateLimitChangedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.util.Optional;

/**
 * 限流额度管理用例：校验入参、归一来源地址、落库（upsert/删除），事务之后发
 * {@link RateLimitChangedEvent} 让本实例额度快照立即重载——运营改完额度，
 * <b>在跑的实例不重启、下一笔请求就按新额度</b>；其他实例靠定时兜底刷新收敛。
 *
 * 额度改动<b>不重置计数</b>：计数器按窗口号分 key，当前窗内立即用新额度判定
 * （调大自然放行更多，调小则当前计数一旦达到新额度即拒），到下一个窗自然从零数，
 * 不需要、也不应该为了改额度去删计数器。
 */
public class RateLimitService {

    private final RateLimitRepository repository;
    private final AppCredentialRepository appRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RateLimitService(RateLimitRepository repository,
                            AppCredentialRepository appRepository,
                            ApplicationEventPublisher events,
                            Clock clock) {
        this.repository = repository;
        this.appRepository = appRepository;
        this.events = events;
        this.clock = clock;
    }

    /** 配置/修改应用总量额度（limit 为 null = 该应用显式不限）。应用必须存在。 */
    public RateQuota setAppQuota(String appNo, Integer limit, String updatedBy) {
        String app = requireRealApp(appNo);
        Integer valid = RateQuota.requireValidLimit(limit);
        RateQuota saved = repository.upsert(RateLimitScope.APP, app, null, valid,
                updatedBy, clock.millis());
        events.publishEvent(RateLimitChangedEvent.quotaChanged("APP", app, null, "upsert"));
        return saved;
    }

    /**
     * 配置/修改某应用下某个来源地址的单独额度（limit 为 null = 该地址显式不限，只受总量约束）。
     * 来源地址必须是合法 IP 字面量，按 ClientIpResolver 归一再存（与鉴权/流水同一口径）。
     */
    public RateQuota setIpQuota(String appNo, String rawIp, Integer limit, String updatedBy) {
        String app = requireRealApp(appNo);
        if (rawIp == null || rawIp.isBlank()) {
            throw new BizException("来源地址 ip 不能为空");
        }
        String canonicalIp = ClientIpResolver.canonicalize(rawIp.trim());
        if (canonicalIp == null) {
            throw new BizException("来源地址必须是合法的 IPv4/IPv6 字面量（不收主机名/网段）：" + rawIp);
        }
        Integer valid = RateQuota.requireValidLimit(limit);
        RateQuota saved = repository.upsert(RateLimitScope.IP, app, canonicalIp, valid,
                updatedBy, clock.millis());
        events.publishEvent(RateLimitChangedEvent.quotaChanged("IP", app, canonicalIp, "upsert"));
        return saved;
    }

    /** 配置/修改全局默认额度（limit 为 null = 默认也不限）。 */
    public RateQuota setDefaultQuota(Integer limit, String updatedBy) {
        Integer valid = RateQuota.requireValidLimit(limit);
        RateQuota saved = repository.upsert(RateLimitScope.DEFAULT, RateQuota.DEFAULT_APP_NO,
                null, valid, updatedBy, clock.millis());
        events.publishEvent(RateLimitChangedEvent.quotaChanged(
                "DEFAULT", RateQuota.DEFAULT_APP_NO, null, "upsert"));
        return saved;
    }

    /** 删掉应用总量行 = 该应用回到「按默认额度走」；没有该行也算成功（幂等）。 */
    public boolean clearAppQuota(String appNo) {
        String app = requireRealApp(appNo);
        boolean changed = repository.delete(RateLimitScope.APP, app, null);
        if (changed) {
            events.publishEvent(RateLimitChangedEvent.quotaChanged("APP", app, null, "delete"));
        }
        return changed;
    }

    /** 删掉来源地址行 = 该地址不再被单独卡（只受应用总量约束）；幂等。 */
    public boolean clearIpQuota(String appNo, String rawIp) {
        String app = requireRealApp(appNo);
        String canonicalIp = ClientIpResolver.canonicalize(rawIp == null ? null : rawIp.trim());
        if (canonicalIp == null) {
            throw new BizException("来源地址必须是合法的 IPv4/IPv6 字面量：" + rawIp);
        }
        boolean changed = repository.delete(RateLimitScope.IP, app, canonicalIp);
        if (changed) {
            events.publishEvent(RateLimitChangedEvent.quotaChanged("IP", app, canonicalIp, "delete"));
        }
        return changed;
    }

    /** 删掉默认行 = 没有默认额度，没单独配的应用一律不限；幂等。 */
    public boolean clearDefaultQuota() {
        boolean changed = repository.delete(RateLimitScope.DEFAULT, RateQuota.DEFAULT_APP_NO, null);
        if (changed) {
            events.publishEvent(RateLimitChangedEvent.quotaChanged(
                    "DEFAULT", RateQuota.DEFAULT_APP_NO, null, "delete"));
        }
        return changed;
    }

    /** 查某行配置（APP/IP/DEFAULT）；没配返回 empty——与「配了但显式不限」区分。 */
    public Optional<RateQuota> getConfigured(RateLimitScope scope, String appNo, String canonicalIp) {
        return repository.find(scope, appNo, canonicalIp);
    }

    /** 校验应用编号合法且应用存在：不允许给一个不存在的应用配额度（停用的应用可以配，启用即生效）。 */
    private String requireRealApp(String appNo) {
        if (appNo == null || appNo.isBlank()) {
            throw new BizException("应用编号不能为空");
        }
        String app = appNo.trim();
        if (RateQuota.DEFAULT_APP_NO.equals(app)) {
            throw new BizException("应用编号不能是保留值 *");
        }
        if (appRepository.findByAppNo(app).isEmpty()) {
            throw new BizException(404, "应用不存在：" + app);
        }
        return app;
    }
}
