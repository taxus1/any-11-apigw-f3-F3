package com.apigw.domain.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 额度模型校验：范围键形状、额度上下界、null=显式不限 的语义。
 */
class RateQuotaTest {

    @Test
    void appRow_shape() {
        RateQuota q = new RateQuota(RateLimitScope.APP, "app-1", null, 100, "ops", null, null);
        assertThat(q.scope()).isEqualTo(RateLimitScope.APP);
        assertThat(q.perMinuteLimit()).isEqualTo(100);
        assertThat(q.unlimited()).isFalse();
    }

    @Test
    void nullLimitMeansExplicitlyUnlimited() {
        RateQuota q = new RateQuota(RateLimitScope.APP, "app-1", null, null, null, null, null);
        assertThat(q.unlimited()).isTrue();
    }

    @Test
    void ipRow_requiresIp() {
        assertThatThrownBy(() -> new RateQuota(RateLimitScope.IP, "app-1", null, 10, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IP 行必须带来源地址");
    }

    @Test
    void appRow_mustNotCarryIp() {
        assertThatThrownBy(() -> new RateQuota(RateLimitScope.APP, "app-1", "1.2.3.4", 10, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APP 行不带 ip");
    }

    @Test
    void defaultRow_isStarOnly() {
        assertThatThrownBy(() -> new RateQuota(RateLimitScope.DEFAULT, "app-1", null, 10, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        RateQuota def = new RateQuota(RateLimitScope.DEFAULT, RateQuota.DEFAULT_APP_NO, null, 10, null, null, null);
        assertThat(def.appNo()).isEqualTo("*");
    }

    @Test
    void appNo_reservedStarRejected() {
        assertThatThrownBy(() -> new RateQuota(RateLimitScope.APP, "*", null, 10, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("保留值");
    }

    @Test
    void limit_bounds() {
        RateQuota.requireValidLimit(1);
        RateQuota.requireValidLimit(RateQuota.MAX_LIMIT);
        assertThat(RateQuota.requireValidLimit(null)).isNull();
        assertThatThrownBy(() -> RateQuota.requireValidLimit(0))
                .isInstanceOf(com.apigw.common.exception.BizException.class);
        assertThatThrownBy(() -> RateQuota.requireValidLimit(-5))
                .isInstanceOf(com.apigw.common.exception.BizException.class);
        assertThatThrownBy(() -> RateQuota.requireValidLimit(RateQuota.MAX_LIMIT + 1))
                .isInstanceOf(com.apigw.common.exception.BizException.class);
    }

    @Test
    void scope_parse() {
        assertThat(RateLimitScope.from("app")).isEqualTo(RateLimitScope.APP);
        assertThat(RateLimitScope.from("IP")).isEqualTo(RateLimitScope.IP);
        assertThat(RateLimitScope.from("default")).isEqualTo(RateLimitScope.DEFAULT);
        assertThatThrownBy(() -> RateLimitScope.from("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
