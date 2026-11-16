package com.comedown.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MySqlContainerTestConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 같은 구글 계정의 첫 로그인이 겹치는 경우(더블 클릭·여러 탭). 각 로그인이 자기 트랜잭션으로 커밋돼야 하므로
 * 테스트 트랜잭션으로 감싸지 않고, 만든 사용자는 끝나고 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class GoogleAccountConcurrentLoginIntegrationTest {

    private static final int ROUNDS = 20;
    private static final int LOGINS = 4;

    @Autowired
    GoogleAccountService service;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'race-%'");
    }

    @Test
    void 같은_구글_계정의_첫_로그인이_겹쳐도_실패_없이_모두_같은_사용자가_된다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(LOGINS);
        try {
            // 겹치는 순서는 실행마다 달라서 여러 번 반복한다(계정은 매번 새것).
            for (int round = 0; round < ROUNDS; round++) {
                String sub = "race-" + UUID.randomUUID();
                CyclicBarrier start = new CyclicBarrier(LOGINS);
                List<Future<Long>> logins = new ArrayList<>();
                for (int i = 0; i < LOGINS; i++) {
                    logins.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return service.loginOrRegister(sub, "race@example.com").userId();
                    }));
                }

                Set<Long> userIds = new HashSet<>();
                for (Future<Long> login : logins) {
                    userIds.add(login.get(30, TimeUnit.SECONDS)); // 늦은 쪽도 예외 없이 끝난다
                }

                assertThat(userIds).as("round %d", round).hasSize(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE google_sub = ?", Integer.class, sub))
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
