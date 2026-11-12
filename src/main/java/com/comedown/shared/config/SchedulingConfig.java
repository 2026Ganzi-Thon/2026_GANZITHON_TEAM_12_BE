package com.comedown.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 같은 애플리케이션 안의 주기 작업(재시도 키 정리, 이후 추천 승인 재확인 등)을 켠다. 메시지 브로커는 쓰지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
