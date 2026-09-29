package com.apigw.infrastructure.ratelimit;

import com.apigw.domain.ratelimit.RateLimitScope;
import com.apigw.domain.ratelimit.RateQuota;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流额度仓储真实 SQL 测试（H2 MODE=MySQL）：
 * - upsert 第一次插、第二次原地更新（不会出现两行）；
 * - 显式不限（null）能落库也能读回，与「没配该行」区分；
 * - APP/IP/DEFAULT 三类行靠唯一键共存不打架（APP/DEFAULT 行 ip 为 NULL）；
 * - 删除幂等；loadAll 一次读齐。
 */
class JdbcRateLimitRepositoryTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private JdbcRateLimitRepository repository;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("ratelimit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
                .addScript("classpath:schema-app.sql")
                .build();
        jdbc = new JdbcTemplate(db);
        repository = new JdbcRateLimitRepository(jdbc, new DataSourceTransactionManager(db));
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    void upsert_app_thenUpdateInPlace() {
        repository.upsert(RateLimitScope.APP, "app-1", null, 100, "ops", 1000L);
        RateQuota second = repository.upsert(RateLimitScope.APP, "app-1", null, 250, "ops2", 2000L);

        assertThat(second.perMinuteLimit()).isEqualTo(250);
        assertThat(second.updatedBy()).isEqualTo("ops2");

        List<RateQuota> all = repository.loadAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).perMinuteLimit()).isEqualTo(250);
        // 库里物理行数也必须是 1：唯一键保证没插出两行
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM gw_rate_limit WHERE scope='APP' AND app_no='app-1'",
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void nullLimit_persistedAsExplicitUnlimited() {
        repository.upsert(RateLimitScope.APP, "app-9", null, null, null, 1000L);
        RateQuota got = repository.find(RateLimitScope.APP, "app-9", null).orElseThrow();
        assertThat(got.perMinuteLimit()).isNull();
        assertThat(got.unlimited()).isTrue();
    }

    @Test
    void missingRow_vs_explicitNullRow() {
        assertThat(repository.find(RateLimitScope.APP, "nope", null)).isEmpty();

        repository.upsert(RateLimitScope.DEFAULT, RateQuota.DEFAULT_APP_NO, null, null, null, 1L);
        RateQuota def = repository.find(RateLimitScope.DEFAULT, RateQuota.DEFAULT_APP_NO, null).orElseThrow();
        assertThat(def.scope()).isEqualTo(RateLimitScope.DEFAULT);
        assertThat(def.perMinuteLimit()).isNull();
    }

    @Test
    void appAndIpRows_coexistByUniqueKey() {
        repository.upsert(RateLimitScope.APP, "app-1", null, 1000, null, 1L);
        repository.upsert(RateLimitScope.IP, "app-1", "1.2.3.4", 10, null, 1L);
        repository.upsert(RateLimitScope.IP, "app-1", "1.2.3.4", 20, null, 2L);
        repository.upsert(RateLimitScope.IP, "app-1", "5.6.7.8", 30, null, 1L);
        repository.upsert(RateLimitScope.DEFAULT, RateQuota.DEFAULT_APP_NO, null, 500, null, 1L);

        assertThat(repository.find(RateLimitScope.IP, "app-1", "1.2.3.4").orElseThrow()
                .perMinuteLimit()).isEqualTo(20);
        assertThat(repository.loadAll()).hasSize(4);

        Integer ipRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gw_rate_limit WHERE scope='IP' AND app_no='app-1'", Integer.class);
        assertThat(ipRows).isEqualTo(2);
    }

    @Test
    void delete_isIdempotent() {
        repository.upsert(RateLimitScope.IP, "app-1", "1.2.3.4", 10, null, 1L);
        assertThat(repository.delete(RateLimitScope.IP, "app-1", "1.2.3.4")).isTrue();
        assertThat(repository.delete(RateLimitScope.IP, "app-1", "1.2.3.4")).isFalse();
        assertThat(repository.find(RateLimitScope.IP, "app-1", "1.2.3.4")).isEmpty();
    }
}
