package com.apigw.application.ratelimit;

import com.apigw.common.exception.BizException;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.app.ClientApp;
import com.apigw.domain.ratelimit.RateLimitRepository;
import com.apigw.domain.ratelimit.RateLimitScope;
import com.apigw.domain.ratelimit.RateQuota;
import com.apigw.proxy.ratelimit.RateLimitChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 额度管理用例：应用存在性校验、来源 IP 归一并校验、null=显式不限透传、
 * upsert/delete 后发变更事件（热更新）、删除幂等不刷事件。
 */
class RateLimitServiceTest {

    private FakeRateRepository rateRepository;
    private FakeAppRepository appRepository;
    private RecordingEventPublisher events;
    private RateLimitService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        rateRepository = new FakeRateRepository();
        appRepository = new FakeAppRepository();
        events = new RecordingEventPublisher();
        service = new RateLimitService(rateRepository, appRepository, events, clock);
        appRepository.addApp("app-1");
    }

    @Test
    void setAppQuota_persistsAndPublishesEvent() {
        RateQuota q = service.setAppQuota("app-1", 100, "ops");
        assertThat(q.perMinuteLimit()).isEqualTo(100);
        assertThat(rateRepository.find(RateLimitScope.APP, "app-1", null).orElseThrow()
                .perMinuteLimit()).isEqualTo(100);
        assertThat(events.events).hasSize(1);
        RateLimitChangedEvent e = (RateLimitChangedEvent) events.events.get(0);
        assertThat(e.scope()).isEqualTo("APP");
        assertThat(e.appNo()).isEqualTo("app-1");
    }

    @Test
    void unknownApp_rejected404() {
        assertThatThrownBy(() -> service.setAppQuota("ghost", 10, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("应用不存在");
        assertThat(events.events).isEmpty();
    }

    @Test
    void setAppQuota_nullMeansExplicitlyUnlimited() {
        RateQuota q = service.setAppQuota("app-1", null, null);
        assertThat(q.perMinuteLimit()).isNull();
        assertThat(rateRepository.find(RateLimitScope.APP, "app-1", null).orElseThrow().unlimited()).isTrue();
    }

    @Test
    void invalidLimit_rejected() {
        assertThatThrownBy(() -> service.setAppQuota("app-1", 0, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.setAppQuota("app-1", -1, null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void setIpQuota_canonicalizesAddress() {
        RateQuota q = service.setIpQuota("app-1", "2001:DB8:0:0:0:0:0:1", 5, null);
        // ClientIpResolver 的规范 IPv6 是每组 %02x 全写
        assertThat(q.ip()).isEqualTo("2001:0db8:0000:0000:0000:0000:0000:0001");
        RateLimitChangedEvent e = (RateLimitChangedEvent) events.events.get(0);
        assertThat(e.scope()).isEqualTo("IP");
        assertThat(e.ip()).isEqualTo("2001:0db8:0000:0000:0000:0000:0000:0001");
    }

    @Test
    void setIpQuota_badAddressRejected() {
        assertThatThrownBy(() -> service.setIpQuota("app-1", "not-an-ip", 5, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("合法的 IPv4/IPv6");
        // 网段/主机名不收
        assertThatThrownBy(() -> service.setIpQuota("app-1", "1.2.3.0/24", 5, null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void defaultQuota_doesNotRequireApp() {
        RateQuota q = service.setDefaultQuota(50, "ops");
        assertThat(q.appNo()).isEqualTo("*");
        assertThat(q.perMinuteLimit()).isEqualTo(50);
        assertThat(rateRepository.find(RateLimitScope.DEFAULT, "*", null)).isPresent();
    }

    @Test
    void clearAppQuota_publishesEventOnlyWhenRowExisted() {
        service.setAppQuota("app-1", 10, null);
        events.clear();

        assertThat(service.clearAppQuota("app-1")).isTrue();
        assertThat(events.events).hasSize(1);
        // 再删一次幂等：没有真变更，不刷事件
        assertThat(service.clearAppQuota("app-1")).isFalse();
        assertThat(events.events).hasSize(1);
    }

    @Test
    void clearDefaultQuota_idempotent() {
        assertThat(service.clearDefaultQuota()).isFalse();
        service.setDefaultQuota(50, null);
        assertThat(service.clearDefaultQuota()).isTrue();
    }

    static class RecordingEventPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();

        void clear() {
            events.clear();
        }

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }

    static class FakeRateRepository implements RateLimitRepository {
        final List<RateQuota> rows = new ArrayList<>();

        @Override
        public RateQuota upsert(RateLimitScope scope, String appNo, String ip, Integer limit, String by, long now) {
            delete(scope, appNo, ip);
            RateQuota q = new RateQuota(scope, appNo, ip, limit, by,
                    Instant.ofEpochMilli(now), Instant.ofEpochMilli(now));
            rows.add(q);
            return q;
        }

        @Override
        public boolean delete(RateLimitScope scope, String appNo, String ip) {
            return rows.removeIf(q -> q.scope() == scope
                    && q.appNo().equals(appNo)
                    && (ip == null ? q.ip() == null : ip.equals(q.ip())));
        }

        @Override
        public Optional<RateQuota> find(RateLimitScope scope, String appNo, String ip) {
            return rows.stream().filter(q -> q.scope() == scope
                    && q.appNo().equals(appNo)
                    && (ip == null ? q.ip() == null : ip.equals(q.ip()))).findFirst();
        }

        @Override
        public List<RateQuota> loadAll() {
            return List.copyOf(rows);
        }
    }

    static class FakeAppRepository implements AppCredentialRepository {
        final List<String> appNos = new ArrayList<>();

        void addApp(String no) {
            appNos.add(no);
        }

        @Override
        public void insert(ClientApp app) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ClientApp> findByAppNo(String appNo) {
            return appNos.contains(appNo) ? Optional.of(mockApp(appNo)) : Optional.empty();
        }

        private ClientApp mockApp(String no) {
            return ClientApp.reconstitute(1L, no, no, "x", null, 1, null, null, null,
                    Instant.parse("2026-01-01T00:00:00Z"), List.of());
        }

        @Override
        public AppPage page(String keyword, long offset, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateEnabled(String appNo, int targetEnabled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addOrigin(String appNo, String canonicalIp, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeOrigin(String appNo, String canonicalIp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AuthApp> loadAllForAuth() {
            throw new UnsupportedOperationException();
        }
    }
}
