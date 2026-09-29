package com.apigw.interfaces.rest.ratelimit;

import com.apigw.application.ratelimit.RateLimitService;
import com.apigw.common.Result;
import com.apigw.common.exception.BizException;
import com.apigw.common.web.ClientIpResolver;
import com.apigw.domain.ratelimit.RateQuota;
import com.apigw.proxy.ratelimit.RateLimitCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 限流额度管理接口。仅在 {@code apigw.rate-limit.enabled=true} 时装配。
 *
 *   GET    /api/gateway/rate-limits/default                 看默认额度配置
 *   PUT    /api/gateway/rate-limits/default                 配/改默认额度（body {"perMinuteLimit":n}，null=默认不限）
 *   DELETE /api/gateway/rate-limits/default                 删默认额度（没默认=未配应用一律不限）
 *   GET    /api/gateway/rate-limits/apps/{appNo}            看某应用总量配置
 *   PUT    /api/gateway/rate-limits/apps/{appNo}            配/改应用每分钟总量
 *   DELETE /api/gateway/rate-limits/apps/{appNo}            删应用总量配置（回落默认）
 *   GET    /api/gateway/rate-limits/apps/{appNo}/ips?ip=    看某应用下某来源地址的单独额度
 *   PUT    /api/gateway/rate-limits/apps/{appNo}/ips?ip=    配/改该来源额度（ip 走 query 兼容 IPv6 冒号）
 *   DELETE /api/gateway/rate-limits/apps/{appNo}/ips?ip=    删该来源单独额度（只受总量约束）
 *   GET    /api/gateway/rate-limits/apps/{appNo}/effective?ip=  查「当前生效」的两层额度（含来源口径）
 *
 * 写接口的 JDBC 工作都切到 boundedElastic，不占 Netty 事件循环。
 */
@RestController
@ConditionalOnProperty(prefix = "apigw.rate-limit", name = "enabled", havingValue = "true")
@RequestMapping("/api/gateway/rate-limits")
public class RateLimitController {

    private final RateLimitService service;
    private final RateLimitCatalog catalog;

    public RateLimitController(RateLimitService service, RateLimitCatalog catalog) {
        this.service = service;
        this.catalog = catalog;
    }

    // ---- 默认额度 ----

    @GetMapping("/default")
    public Mono<Result<RateQuotaVO>> getDefault() {
        return Mono.fromCallable(() -> service.getConfigured(
                        com.apigw.domain.ratelimit.RateLimitScope.DEFAULT, RateQuota.DEFAULT_APP_NO, null)
                .map(RateQuotaVO::of).orElse(null))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PutMapping("/default")
    public Mono<Result<RateQuotaVO>> setDefault(@RequestBody(required = false) RateQuotaUpdateVO body,
                                                @RequestHeader(value = "X-Created-By", required = false) String by) {
        Integer limit = body == null ? null : body.perMinuteLimit();
        return Mono.fromCallable(() -> service.setDefaultQuota(limit, by))
                .subscribeOn(Schedulers.boundedElastic())
                .map(RateQuotaVO::of)
                .map(Result::ok);
    }

    @DeleteMapping("/default")
    public Mono<Result<Void>> clearDefault() {
        return Mono.fromRunnable(service::clearDefaultQuota)
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(Result.ok());
    }

    // ---- 应用总量 ----

    @GetMapping("/apps/{appNo}")
    public Mono<Result<RateQuotaVO>> getApp(@PathVariable String appNo) {
        return Mono.fromCallable(() -> service.getConfigured(
                        com.apigw.domain.ratelimit.RateLimitScope.APP, appNo.trim(), null)
                .map(RateQuotaVO::of).orElse(null))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PutMapping("/apps/{appNo}")
    public Mono<Result<RateQuotaVO>> setApp(@PathVariable String appNo,
                                            @RequestBody(required = false) RateQuotaUpdateVO body,
                                            @RequestHeader(value = "X-Created-By", required = false) String by) {
        Integer limit = body == null ? null : body.perMinuteLimit();
        return Mono.fromCallable(() -> service.setAppQuota(appNo, limit, by))
                .subscribeOn(Schedulers.boundedElastic())
                .map(RateQuotaVO::of)
                .map(Result::ok);
    }

    @DeleteMapping("/apps/{appNo}")
    public Mono<Result<Void>> clearApp(@PathVariable String appNo) {
        return Mono.fromRunnable(() -> service.clearAppQuota(appNo))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(Result.ok());
    }

    // ---- 应用下来源地址 ----

    @GetMapping("/apps/{appNo}/ips")
    public Mono<Result<RateQuotaVO>> getIp(@PathVariable String appNo, @RequestParam String ip) {
        String canonical = canonicalIp(ip);
        return Mono.fromCallable(() -> service.getConfigured(
                        com.apigw.domain.ratelimit.RateLimitScope.IP, appNo.trim(), canonical)
                .map(RateQuotaVO::of).orElse(null))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PutMapping("/apps/{appNo}/ips")
    public Mono<Result<RateQuotaVO>> setIp(@PathVariable String appNo,
                                           @RequestParam String ip,
                                           @RequestBody(required = false) RateQuotaUpdateVO body,
                                           @RequestHeader(value = "X-Created-By", required = false) String by) {
        Integer limit = body == null ? null : body.perMinuteLimit();
        return Mono.fromCallable(() -> service.setIpQuota(appNo, ip, limit, by))
                .subscribeOn(Schedulers.boundedElastic())
                .map(RateQuotaVO::of)
                .map(Result::ok);
    }

    @DeleteMapping("/apps/{appNo}/ips")
    public Mono<Result<RateQuotaVO>> clearIp(@PathVariable String appNo, @RequestParam String ip) {
        return Mono.fromRunnable(() -> service.clearIpQuota(appNo, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(Result.ok());
    }

    /** 查「当前生效」的两层额度：生效数值 + 来源口径（APP/DEFAULT/IP/UNLIMITED）。 */
    @GetMapping("/apps/{appNo}/effective")
    public Mono<Result<EffectiveRateQuotaVO>> effective(@PathVariable String appNo,
                                                        @RequestParam(required = false) String ip) {
        String app = appNo.trim();
        String canonical = ip == null || ip.isBlank() ? null : canonicalIp(ip);
        RateLimitCatalog.EffectiveQuotaView view = catalog.describe(app, canonical);
        return Mono.just(Result.ok(new EffectiveRateQuotaVO(
                app, canonical,
                view.appPerMinute(), view.appSource(),
                view.ipPerMinute(), view.ipSource())));
    }

    private static String canonicalIp(String rawIp) {
        String canonical = ClientIpResolver.canonicalize(rawIp == null ? null : rawIp.trim());
        if (canonical == null) {
            throw new BizException("来源地址必须是合法的 IPv4/IPv6 字面量（不收主机名/网段）：" + rawIp);
        }
        return canonical;
    }
}
