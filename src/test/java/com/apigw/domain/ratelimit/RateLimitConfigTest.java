package com.apigw.domain.ratelimit;

import com.apigw.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimitConfigTest {

    @Test
    void nullMeansInheritDefaultAndMinusOneMeansUnlimited() {
        RateLimitConfig config = RateLimitConfig.of(null, -1);

        assertNull(config.appLimit());
        assertEquals(-1, config.sourceLimit());
    }

    @Test
    void zeroIsNotAValidQuota() {
        BizException error = assertThrows(BizException.class,
                () -> RateLimitConfig.of(0, 100));

        assertEquals("限流额度必须为正整数，-1 表示不限流", error.getMessage());
    }
}
