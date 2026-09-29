package com.apigw.interfaces.rest.ratelimit;

import com.apigw.application.ratelimit.RateLimitAdminService;
import com.apigw.common.Result;
import com.apigw.common.exception.BizException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 限流额度管理接口。
 *
 * 默认额度：
 * - GET /api/gateway/rate-limit/default
 * - PUT /api/gateway/rate-limit/default
 * - DELETE /api/gateway/rate-limit/default
 *
 * 单应用额度：
 * - GET /api/gateway/apps/{appNo}/rate-limit
 * - PUT /api/gateway/apps/{appNo}/rate-limit
 * - DELETE /api/gateway/apps/{appNo}/rate-limit
 */
@RestController
@ConditionalOnExpression("${apigw.rate-limit.enabled:true} and ${apigw.app-auth.enabled:false}")
@RequestMapping("/api/gateway")
public class RateLimitController {

    private final RateLimitAdminService service;

    public RateLimitController(RateLimitAdminService service) {
        this.service = service;
    }

    @GetMapping("/rate-limit/default")
    public Mono<Result<RateLimitConfigVO>> getDefault() {
        return service.getDefault().map(RateLimitConfigVO::from).map(Result::ok);
    }

    @PutMapping("/rate-limit/default")
    public Mono<Result<RateLimitConfigVO>> setDefault(@RequestBody(required = false) RateLimitConfigRequest request) {
        return service.setDefault(nonNull(request).toDomain()).map(RateLimitConfigVO::from).map(Result::ok);
    }

    @DeleteMapping("/rate-limit/default")
    public Mono<Result<Void>> clearDefault() {
        return service.clearDefault().thenReturn(Result.ok());
    }

    @GetMapping("/apps/{appNo}/rate-limit")
    public Mono<Result<RateLimitConfigVO>> getApp(@PathVariable String appNo) {
        return service.getApp(appNo).map(RateLimitConfigVO::from).map(Result::ok);
    }

    @PutMapping("/apps/{appNo}/rate-limit")
    public Mono<Result<RateLimitConfigVO>> setApp(@PathVariable String appNo,
                                                  @RequestBody(required = false) RateLimitConfigRequest request) {
        return service.setApp(appNo, nonNull(request).toDomain()).map(RateLimitConfigVO::from).map(Result::ok);
    }

    @DeleteMapping("/apps/{appNo}/rate-limit")
    public Mono<Result<Void>> clearApp(@PathVariable String appNo) {
        return service.clearApp(appNo).thenReturn(Result.ok());
    }

    private static RateLimitConfigRequest nonNull(RateLimitConfigRequest request) {
        if (request == null) {
            throw new BizException("请求体不能为空");
        }
        return request;
    }
}
