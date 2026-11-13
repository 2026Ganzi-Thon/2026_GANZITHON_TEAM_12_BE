package com.comedown.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RequestHasherTest {

    private final RequestHasher hasher = new RequestHasher(JsonMapper.builder().build());

    record Measurement(String source, List<Double> t, Boolean torch) {
    }

    @Test
    void 같은_내용은_같은_해시이고_소문자_hex_64자() {
        String a = hasher.hash(new Measurement("CAMERA", List.of(0.0, 16.7), true));
        String b = hasher.hash(new Measurement("CAMERA", List.of(0.0, 16.7), true));

        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void 내용이_다르면_해시가_다르다() {
        assertThat(hasher.hash(new Measurement("CAMERA", List.of(0.0, 16.7), true)))
                .isNotEqualTo(hasher.hash(new Measurement("CAMERA", List.of(0.0, 16.8), true)));
    }

    @Test
    void 맵의_키_순서가_달라도_같은_해시() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("score", 3);
        first.put("alcohol", false);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("alcohol", false);
        second.put("score", 3);

        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(second));
    }
}
