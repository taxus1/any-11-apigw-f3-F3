package com.apigw.proxy.ratelimit;

/**
 * 限流额度配置变更事件。任何额度 upsert/删除的事务提交之后由应用服务发布：
 * 本实例的额度快照收到后立即重载，运营刚改完的额度对<b>下一笔新请求</b>立即生效，
 * 不等重启；别的实例收不到进程内事件，靠 {@code apigw.rate-limit.refresh-interval}
 * 定时兜底刷新在一个周期内收敛。
 *
 * @param scope 变更范围（APP/IP/DEFAULT）
 * @param appNo 变更的应用编号（DEFAULT 行为 {@code *}）
 * @param ip    IP 行的来源地址，其余为 null
 */
public record RateLimitChangedEvent(String scope, String appNo, String ip, String reason) {

    public static RateLimitChangedEvent quotaChanged(String scope, String appNo, String ip, String reason) {
        return new RateLimitChangedEvent(scope, appNo, ip, reason);
    }
}
