package com.apigw.interfaces.rest.ratelimit;

/**
 * 「当前生效额度」查询视图：既给数值，也说清数值来自哪一级配置。
 *
 * 应用层 source 取值：
 * - APP       ：该应用自己配了总量（perMinuteLimit 可能为 null=对该应用显式不限）；
 * - DEFAULT   ：该应用没单独配，按全局默认额度走；
 * - UNLIMITED ：应用没配、默认也没配（或默认显式不限），这一层不限。
 *
 * 来源层 source 取值：
 * - IP        ：给该应用 + 该来源单独配了额度（null=该地址显式不限，只受总量约束）；
 * - UNLIMITED ：没给这个来源单独配，来源层不卡（只受应用总量约束）。
 *
 * {@code perMinuteLimit} 为 null 即不限；「当前生效」是各实例内存额度快照里的值，
 * 本实例随额度变更事件即时更新，其他实例在一个刷新周期（默认 10s）内收敛。
 */
public record EffectiveRateQuotaVO(String appNo,
                                   String ip,
                                   Integer appPerMinuteLimit,
                                   String appLimitSource,
                                   Integer ipPerMinuteLimit,
                                   String ipLimitSource) {
}
