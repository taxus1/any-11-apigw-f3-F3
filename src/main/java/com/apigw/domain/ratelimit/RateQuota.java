package com.apigw.domain.ratelimit;

import com.apigw.common.exception.BizException;

import java.time.Instant;

/**
 * 一行限流额度配置。
 *
 * 额度语义（每分钟次数）：
 * - {@code perMinuteLimit} 为正整数：该范围在任意一个自然分钟窗口内最多放行这么多次；
 * - {@code perMinuteLimit} 为 null：这一层「显式不限」（区别于「没配这行」——没配要回落默认）。
 *
 * 范围键：
 * - APP     行：{@code appNo} 有效、{@code ip} 为 null；
 * - IP      行：{@code appNo} 有效、{@code ip} 是规范化 IP 字面量；
 * - DEFAULT 行：{@code appNo} 固定 {@link #DEFAULT_APP_NO}、{@code ip} 为 null。
 *
 * 额度合法性（上下界）统一在 {@link #requireValidLimit} 校验，控制器和仓储都不能绕过：
 * 上限封顶 1,000,000 次/分，防止配错一个超大整数把计数器/网关内存撑爆。
 */
public record RateQuota(RateLimitScope scope,
                        String appNo,
                        String ip,
                        Integer perMinuteLimit,
                        String updatedBy,
                        Instant createdAt,
                        Instant updatedAt) {

    /** DEFAULT 行的应用编号占位（不是一个合法业务编号：业务编号字符集不含 '*'）。 */
    public static final String DEFAULT_APP_NO = "*";

    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 1_000_000;

    public RateQuota {
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        if (appNo == null || appNo.isBlank()) {
            throw new IllegalArgumentException("appNo 不能为空");
        }
        appNo = appNo.trim();
        switch (scope) {
            case APP -> {
                if (DEFAULT_APP_NO.equals(appNo)) {
                    throw new IllegalArgumentException("应用编号不能是保留值 *");
                }
                if (ip != null) {
                    throw new IllegalArgumentException("APP 行不带 ip");
                }
            }
            case IP -> {
                if (DEFAULT_APP_NO.equals(appNo)) {
                    throw new IllegalArgumentException("应用编号不能是保留值 *");
                }
                if (ip == null || ip.isBlank()) {
                    throw new IllegalArgumentException("IP 行必须带来源地址");
                }
                ip = ip.trim();
            }
            case DEFAULT -> {
                if (!DEFAULT_APP_NO.equals(appNo) || ip != null) {
                    throw new IllegalArgumentException("DEFAULT 行固定为 scope=DEFAULT, appNo=*");
                }
            }
        }
        requireValidLimit(perMinuteLimit);
    }

    /** 额度：null=这一层显式不限；非 null 必须是 1..1,000,000 的正整数。 */
    public static Integer requireValidLimit(Integer limit) {
        if (limit == null) {
            return null;
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BizException(
                    "每分钟额度必须是 " + MIN_LIMIT + ".." + MAX_LIMIT + " 的正整数，或不传/传 null 表示不限");
        }
        return limit;
    }

    /** 落库/装载用的工厂：不经业务校验外的额外规则，仅做形状约束。 */
    public static RateQuota reconstitute(RateLimitScope scope, String appNo, String ip,
                                         Integer perMinuteLimit, String updatedBy,
                                         Instant createdAt, Instant updatedAt) {
        return new RateQuota(scope, appNo, ip, perMinuteLimit, updatedBy, createdAt, updatedAt);
    }

    public boolean unlimited() {
        return perMinuteLimit == null;
    }
}
