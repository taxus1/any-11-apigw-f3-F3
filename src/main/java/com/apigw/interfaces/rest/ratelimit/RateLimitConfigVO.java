package com.apigw.interfaces.rest.ratelimit;

import com.apigw.application.ratelimit.RateLimitAdminService;
import com.apigw.domain.ratelimit.RateLimitConfig;

/**
 * 限流额度查询/保存结果。
 *
 * configured 展示该应用自己的覆盖值；effective 展示当前真正参与判定的额度。
 * null=没有额度约束，-1=显式不限，正整数=每分钟最多请求数。
 */
public record RateLimitConfigVO(String appNo,
                                LimitVO configured,
                                LimitVO effective) {

    public static RateLimitConfigVO from(RateLimitAdminService.RateLimitView view) {
        return new RateLimitConfigVO(view.appNo(),
                toLimit(view.configured()), toLimit(view.effective()));
    }

    private static LimitVO toLimit(RateLimitConfig config) {
        return new LimitVO(config.appLimit(), config.sourceLimit());
    }

    public record LimitVO(Integer appLimitPerMinute, Integer sourceLimitPerMinute) {
    }
}
