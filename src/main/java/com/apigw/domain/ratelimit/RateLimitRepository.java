package com.apigw.domain.ratelimit;

import java.util.List;
import java.util.Optional;

/**
 * 限流额度配置的持久化端口（接口在领域层，JDBC 实现在基础设施层）。
 *
 * 只持久化「额度配置」（低频运营写）；转发链路上每个窗口的计数不在库里，在 Redis。
 *
 * 写口径：
 * - {@link #upsert} 是按 (scope, appNo, ip) 唯一键的「存在即更新、不存在即插入」，
 *   一行配置在同一个事务里落定，并发改同一范围最后一次提交生效（额度以最后配置为准，
 *   不存在两个人各改一半的中间态）；
 * - {@link #delete} 幂等，删掉一行 = 这一层回到「没配」，请求判定时回落 DEFAULT/不限。
 *
 * 读口径：
 * - {@link #loadAll()} 一次读齐全表给内存额度快照（额度行数量 = 应用数 + 配了 IP 的条数，
 *   量级很小），转发高频路径只查内存，不每笔查库。
 */
public interface RateLimitRepository {

    /** 按 (scope, appNo, ip) upsert；createdAt/updatedAt 由调用方给定时钟。返回落库后的行。 */
    RateQuota upsert(RateLimitScope scope, String appNo, String ip,
                     Integer perMinuteLimit, String updatedBy, long nowEpochMillis);

    /** 删除一行额度；返回是否真的删掉了（没有该行返回 false，调用方仍按幂等成功处理）。 */
    boolean delete(RateLimitScope scope, String appNo, String ip);

    /** 读单条当前配置；没配返回 empty（注意：与「配了显式不限」区分，后者返回 limit=null 的行）。 */
    Optional<RateQuota> find(RateLimitScope scope, String appNo, String ip);

    /** 读全部额度行，供额度快照一次装载。 */
    List<RateQuota> loadAll();
}
