package com.apigw.infrastructure.ratelimit;

import com.apigw.application.ratelimit.RateLimitService;
import com.apigw.interfaces.rest.ratelimit.RateLimitController;
import com.apigw.proxy.ratelimit.RateLimitWebFilter;
import com.apigw.proxy.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 默认（限流开关关闭）形态：限流的 bean/过滤器/控制器一个都不进容器，
 * 转发链路与未做限流时完全一致，上下文照常启动。
 */
@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6399"
})
class RateLimitDisabledSmokeTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void noRateLimitBeans_andContextStillStarts() {
        assertThatCode(() -> ctx.getBean(RateLimitService.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatCode(() -> ctx.getBean(RateLimitController.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatCode(() -> ctx.getBean(RateLimitWebFilter.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatCode(() -> ctx.getBean(RateLimiter.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }
}
