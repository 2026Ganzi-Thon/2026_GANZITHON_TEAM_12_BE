package com.comedown.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 통합 테스트용 MySQL 8.4 컨테이너. docker-compose.yml과 같은 이미지·문자셋을 쓴다.
 * <pre>{@code @Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})}</pre>
 * Docker가 꺼져 있으면 컨텍스트 생성이 실패한다(통합 검증 완료로 보고하지 않는다).
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerTestConfig {

    @Bean
    @ServiceConnection
    public MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:8.4")
                .withDatabaseName("comedown")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")
                .withEnv("TZ", "UTC");
    }
}
