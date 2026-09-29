package com.apigw.interfaces.rest.ratelimit;

import com.apigw.domain.ratelimit.RateQuota;

import java.time.Instant;

/**
 * 一行额度配置的对外视图。{@code perMinuteLimit} 为 null 表示该层显式不限。
 */
public record RateQuotaVO(String scope,
                          String appNo,
                          String ip,
                          Integer perMinuteLimit,
                          String updatedBy,
                          Instant createdAt,
                          Instant updatedAt) {

    public static RateQuotaVO of(RateQuota q) {
        return new RateQuotaVO(q.scope().name(), q.appNo(), q.ip(),
                q.perMinuteLimit(), q.updatedBy(), q.createdAt(), q.updatedAt());
    }
}
