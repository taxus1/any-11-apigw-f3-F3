package com.apigw.interfaces.rest.ratelimit;

/**
 * 额度配置请求体。
 *
 * @param perMinuteLimit 每分钟调用次数上限；null 或不传 = 这一层「显式不限」
 *                       （删除配置行才是「回到默认/不单独卡」，两者语义不同）。
 */
public record RateQuotaUpdateVO(Integer perMinuteLimit) {
}
