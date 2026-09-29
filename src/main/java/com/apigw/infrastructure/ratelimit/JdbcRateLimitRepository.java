package com.apigw.infrastructure.ratelimit;

import com.apigw.domain.ratelimit.RateLimitRepository;
import com.apigw.domain.ratelimit.RateLimitScope;
import com.apigw.domain.ratelimit.RateQuota;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 限流额度配置的 JDBC 实现。
 *
 * - 全部参数走 PreparedStatement 绑定，没有字符串拼进 SQL；
 * - upsert 在**同一事务**里「查存在 → 插入或更新」：唯一键 uk_scope_app_ip 兜底，
 *   并发改同一范围不会插出两行（撞键的一方更新落空后下一次配置即对齐，额度是全量覆盖语义，
 *   不存在两人各写一半的中间态）；
 * - 不用数据库方言相关的 ON DUPLICATE KEY / MERGE，靠事务 + 唯一索引保持 MySQL 与测试用
 *   H2（MODE=MySQL）行为一致；
 * - 这张表只放配置，计数器在 Redis，所以这里没有任何高频写。
 */
public class JdbcRateLimitRepository implements RateLimitRepository {

    private static final String T_RATE_LIMIT = "gw_rate_limit";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcRateLimitRepository(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public RateQuota upsert(RateLimitScope scope, String appNo, String ip,
                            Integer perMinuteLimit, String updatedBy, long nowEpochMillis) {
        Instant now = Instant.ofEpochMilli(nowEpochMillis);
        return tx.execute(status -> {
            List<RateQuota> existing = jdbc.query(
                    "SELECT * FROM " + T_RATE_LIMIT + " WHERE scope = ? AND app_no = ?"
                            + (ip == null ? " AND ip IS NULL" : " AND ip = ?"),
                    QUOTA_MAPPER, ip == null ? new Object[]{scope.name(), appNo}
                            : new Object[]{scope.name(), appNo, ip});
            if (existing.isEmpty()) {
                jdbc.update("INSERT INTO " + T_RATE_LIMIT
                                + " (scope, app_no, ip, per_minute_limit, updated_by, created_at, updated_at)"
                                + " VALUES (?,?,?,?,?,?,?)",
                        scope.name(), appNo, ip, perMinuteLimit, updatedBy,
                        Timestamp.from(now), Timestamp.from(now));
            } else {
                // SET 3 个参数 + WHERE scope/app_no 2 个；ip 为 NULL 走 IS NULL（不占参数位）
                if (ip == null) {
                    jdbc.update("UPDATE " + T_RATE_LIMIT
                                    + " SET per_minute_limit = ?, updated_by = ?, updated_at = ?"
                                    + " WHERE scope = ? AND app_no = ? AND ip IS NULL",
                            perMinuteLimit, updatedBy, Timestamp.from(now), scope.name(), appNo);
                } else {
                    jdbc.update("UPDATE " + T_RATE_LIMIT
                                    + " SET per_minute_limit = ?, updated_by = ?, updated_at = ?"
                                    + " WHERE scope = ? AND app_no = ? AND ip = ?",
                            perMinuteLimit, updatedBy, Timestamp.from(now), scope.name(), appNo, ip);
                }
            }
            return new RateQuota(scope, appNo, ip, perMinuteLimit, updatedBy, now, now);
        });
    }

    @Override
    public boolean delete(RateLimitScope scope, String appNo, String ip) {
        int affected = ip == null
                ? jdbc.update("DELETE FROM " + T_RATE_LIMIT
                        + " WHERE scope = ? AND app_no = ? AND ip IS NULL", scope.name(), appNo)
                : jdbc.update("DELETE FROM " + T_RATE_LIMIT
                        + " WHERE scope = ? AND app_no = ? AND ip = ?", scope.name(), appNo, ip);
        return affected > 0;
    }

    @Override
    public Optional<RateQuota> find(RateLimitScope scope, String appNo, String ip) {
        List<RateQuota> rows = jdbc.query(
                "SELECT * FROM " + T_RATE_LIMIT + " WHERE scope = ? AND app_no = ?"
                        + (ip == null ? " AND ip IS NULL" : " AND ip = ?"),
                QUOTA_MAPPER, ip == null ? new Object[]{scope.name(), appNo}
                        : new Object[]{scope.name(), appNo, ip});
        return rows.stream().findFirst();
    }

    @Override
    public List<RateQuota> loadAll() {
        return jdbc.query("SELECT * FROM " + T_RATE_LIMIT
                + " ORDER BY scope, app_no, ip", QUOTA_MAPPER);
    }

    private static final RowMapper<RateQuota> QUOTA_MAPPER = (rs, n) -> RateQuota.reconstitute(
            RateLimitScope.valueOf(rs.getString("scope")),
            rs.getString("app_no"),
            rs.getString("ip"),
            (Integer) rs.getObject("per_minute_limit"),
            rs.getString("updated_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
}
