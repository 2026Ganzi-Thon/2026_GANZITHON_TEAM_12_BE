package com.comedown.cbi.api;

import com.comedown.cbi.domain.CbiQuestionnaire;
import com.comedown.shared.security.CurrentUser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/cbi/questions} → 200. A3 문항(HTTP 책임: cbi.api). 로그인만 필요하다(온보딩 중 허용).
 * 화면은 순서를 섞어 보여주고 서버의 no는 고정한다. 역채점 여부는 서버만 안다.
 */
@RestController
public class CbiQuestionsController {

    record Question(int no, String subscale, String scale, String text) {
    }

    record QuestionsResponse(String version, Map<String, List<String>> scales, List<Question> items) {
    }

    private static final QuestionsResponse RESPONSE = new QuestionsResponse(
            CbiQuestionnaire.VERSION,
            scales(),
            CbiQuestionnaire.ITEMS.stream()
                    .map(i -> new Question(i.no(), i.subscale().name(), i.scale().name(), i.text()))
                    .toList());

    @GetMapping("/api/cbi/questions")
    public QuestionsResponse questions(CurrentUser user) {
        return RESPONSE;
    }

    /** 척도별 보기(option 0 → 4 순서). 응답 키 순서를 고정한다. */
    private static Map<String, List<String>> scales() {
        Map<String, List<String>> scales = new LinkedHashMap<>();
        for (CbiQuestionnaire.Scale scale : CbiQuestionnaire.Scale.values()) {
            scales.put(scale.name(), CbiQuestionnaire.SCALE_LABELS.get(scale));
        }
        return Collections.unmodifiableMap(scales);
    }
}
