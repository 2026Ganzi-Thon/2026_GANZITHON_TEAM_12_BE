package com.comedown.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TitleHasherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final TitleHasher hasher = new TitleHasher(KEY);

    @Test
    void 공백과_대소문자를_정규화해_같은_제목은_같은_값을_준다() {
        assertThat(hasher.hash(1, " Capstone   Meeting ")).isEqualTo(hasher.hash(1, "capstone meeting"));
    }

    @Test
    void 사용자가_다르면_같은_제목도_다른_값이다() {
        assertThat(hasher.hash(1, "스터디")).isNotEqualTo(hasher.hash(2, "스터디"));
    }

    @Test
    void 제목이나_키가_없으면_null이고_원문을_담지_않는다() {
        assertThat(hasher.hash(1, null)).isNull();
        assertThat(hasher.hash(1, "  ")).isNull();
        assertThat(new TitleHasher("").hash(1, "스터디")).isNull();
        assertThat(hasher.hash(1, "스터디")).hasSize(64).doesNotContain("스터디");
    }
}
