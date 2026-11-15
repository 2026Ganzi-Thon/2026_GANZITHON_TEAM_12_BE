package com.comedown.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MySqlContainerTestConfig;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 로그아웃({@code POST /api/auth/logout})이 Spring Session(JDBC)의 세션 행까지 지우는지 MySQL로 확인한다.
 * 세션은 local 전용 테스트 로그인으로 만든다(SESSION 쿠키).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class LogoutSessionIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void setUp() {
        String sub = "logout-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, created_at, last_login_at) "
                + "VALUES (?, 'PROFILE', '2026-10-01 00:00:00', '2026-10-01 00:00:00')", sub);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", String.valueOf(userId));
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'logout-%'");
    }

    @Test
    void 로그아웃하면_세션_행이_지워지고_같은_쿠키로는_401() throws Exception {
        Cookie session = mvc.perform(post("/api/dev/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + userId + "}"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("SESSION");
        assertThat(session).isNotNull();
        assertThat(sessionRows()).isEqualTo(1);
        mvc.perform(get("/api/cbi/questions").cookie(session)).andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").cookie(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(sessionRows()).isZero();
        mvc.perform(get("/api/cbi/questions").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private int sessionRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", Integer.class,
                String.valueOf(userId));
    }
}
