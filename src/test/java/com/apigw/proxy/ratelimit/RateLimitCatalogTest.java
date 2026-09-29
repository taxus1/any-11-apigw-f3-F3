package com.apigw.proxy.ratelimit;

import com.apigw.domain.ratelimit.RateLimitRepository;
import com.apigw.domain.ratelimit.RateLimitScope;
import com.apigw.domain.ratelimit.RateQuota;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 额度快照的判定口径（不依赖库：用内存假仓储）：
 * - 应用层 APP → DEFAULT → 不限；IP 层只看专属行，没配即不限（不回落默认）；
 * - 「配了显式不限(null)」与「没配这行」必须区分；
 * - refresh 之后新请求按新额度（热更新）；
 * - 从没加载成功时两层都不限（可用性取向）。
 */
class RateLimitCatalogTest {

    private FakeRepository repository;
    private RateLimitCatalog catalog;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        catalog = new RateLimitCatalog(repository);
    }

    @Test
    void noSnapshot_everythingUnlimited() {
        RateLimitCatalog.EffectiveQuota q = catalog.resolve("app-x", "1.1.1.1");
        assertThat(q.nothingLimited()).isTrue();
    }

    @Test
    void appFallback_appThenDefaultThenUnlimited() {
        repository.rows.add(quota(RateLimitScope.APP, "app-1", null, 100));
        repository.rows.add(quota(RateLimitScope.DEFAULT, "*", null, 50));
        catalog.refreshBlock(Duration.ofSeconds(5));

        // 应用自己配了 → 用应用值，不回落默认
        assertThat(catalog.resolve("app-1", null).appPerMinute()).isEqualTo(100);
        // 应用没配 → 默认
        assertThat(catalog.resolve("app-2", null).appPerMinute()).isEqualTo(50);
    }

    @Test
    void noDefault_meansUnlimited() {
        catalog.refreshBlock(Duration.ofSeconds(5));
        RateLimitCatalog.EffectiveQuota q = catalog.resolve("app-2", "9.9.9.9");
        assertThat(q.appPerMinute()).isNull();
        assertThat(q.ipPerMinute()).isNull();
        assertThat(q.nothingLimited()).isTrue();
    }

    @Test
    void explicitNullApp_doesNotFallBackToDefault() {
        repository.rows.add(quota(RateLimitScope.APP, "app-1", null, null)); // 该应用显式不限
        repository.rows.add(quota(RateLimitScope.DEFAULT, "*", null, 50));
        catalog.refreshBlock(Duration.ofSeconds(5));

        assertThat(catalog.resolve("app-1", null).appPerMinute()).isNull();
        RateLimitCatalog.EffectiveQuotaView view = catalog.describe("app-1", null);
        assertThat(view.appSource()).isEqualTo("APP");
        assertThat(view.appPerMinute()).isNull();
    }

    @Test
    void ipLayer_isIndependent_andDoesNotFallBackToDefault() {
        repository.rows.add(quota(RateLimitScope.DEFAULT, "*", null, 50));
        repository.rows.add(quota(RateLimitScope.IP, "app-1", "1.1.1.1", 5));
        catalog.refreshBlock(Duration.ofSeconds(5));

        RateLimitCatalog.EffectiveQuota noisy = catalog.resolve("app-1", "1.1.1.1");
        assertThat(noisy.appPerMinute()).isEqualTo(50); // 总量仍按默认
        assertThat(noisy.ipPerMinute()).isEqualTo(5);   // 该地址单独卡

        // 同应用的其他正常来源不受影响：IP 层没配 = 不限
        RateLimitCatalog.EffectiveQuota quiet = catalog.resolve("app-1", "2.2.2.2");
        assertThat(quiet.ipPerMinute()).isNull();
        assertThat(quiet.appPerMinute()).isEqualTo(50);
    }

    @Test
    void explicitNullIp_meansUnlimitedButStillTrackedAsConfigured() {
        repository.rows.add(quota(RateLimitScope.IP, "app-1", "1.1.1.1", null));
        catalog.refreshBlock(Duration.ofSeconds(5));

        assertThat(catalog.resolve("app-1", "1.1.1.1").ipPerMinute()).isNull();
        RateLimitCatalog.EffectiveQuotaView view = catalog.describe("app-1", "1.1.1.1");
        assertThat(view.ipSource()).isEqualTo("IP");
        assertThat(view.ipPerMinute()).isNull();
    }

    @Test
    void refresh_picksUpNewQuotaImmediately() {
        repository.rows.add(quota(RateLimitScope.APP, "app-1", null, 100));
        catalog.refreshBlock(Duration.ofSeconds(5));
        assertThat(catalog.resolve("app-1", null).appPerMinute()).isEqualTo(100);

        // 运营改额度（新快照），下一笔请求立即按新值
        repository.rows.clear();
        repository.rows.add(quota(RateLimitScope.APP, "app-1", null, 3));
        catalog.refreshBlock(Duration.ofSeconds(5));
        assertThat(catalog.resolve("app-1", null).appPerMinute()).isEqualTo(3);
    }

    @Test
    void refreshFailure_keepsOldSnapshot() {
        repository.rows.add(quota(RateLimitScope.APP, "app-1", null, 100));
        catalog.refreshBlock(Duration.ofSeconds(5));

        repository.fail = true;
        catalog.refreshBlock(Duration.ofSeconds(5));
        // 旧快照沿用
        assertThat(catalog.resolve("app-1", null).appPerMinute()).isEqualTo(100);
    }

    private static RateQuota quota(RateLimitScope scope, String appNo, String ip, Integer limit) {
        return RateQuota.reconstitute(scope, appNo, ip, limit, null, null, null);
    }

    static class FakeRepository implements RateLimitRepository {
        final List<RateQuota> rows = new ArrayList<>();
        boolean fail = false;

        @Override
        public RateQuota upsert(RateLimitScope scope, String appNo, String ip, Integer limit, String by, long now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean delete(RateLimitScope scope, String appNo, String ip) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RateQuota> find(RateLimitScope scope, String appNo, String ip) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RateQuota> loadAll() {
            if (fail) {
                throw new IllegalStateException("db down");
            }
            return List.copyOf(rows);
        }
    }
}
