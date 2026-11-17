package com.comedown.ai.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 해석 문구 프롬프트와 결과 검증(ai 소유 제품 규칙, 순수 계산). 모델은 주어진 사실을 자연스러운 문장으로 옮기기만 하고
 * 계산·판단·진단을 하지 않는다.
 */
final class InterpretationPrompt {

    /** 프롬프트를 바꾸면 올린다(캐시 키에 들어가 이전 문구를 다시 쓰지 않게 한다). */
    static final String VERSION = "interp-v2";
    static final int MAX_LENGTH = 200;

    private static final String HEAD = """
            너는 대학생의 번아웃 예방을 돕는 서비스 'Come & Down'의 안내 문구를 쓴다.
            규칙:
            1. 아래 '사실'에 있는 내용만 말한다. 숫자를 새로 만들거나 바꾸지 않는다.
               사실의 낱말을 다른 뜻의 말로 바꾸지 않는다(예: 측정값을 활동량이나 컨디션으로 바꾸지 않는다).
            2. 진단, 질병, 치료, 약, 병원에 대한 말을 하지 않는다. 원인을 추측하지 않는다.
            """;
    private static final String TAIL = """
            3. 한국어 해요체로 두 문장 이내, 120자 이내로 쓴다. 부드럽고 담백하게 쓴다.
            4. 따옴표, 이모지, 줄바꿈, 머리말 없이 문장만 쓴다.""";

    /** 해석·요약: 사실을 전하기만 한다. */
    static final String SYSTEM = HEAD
            + "   조언이나 권유, 몸 상태에 대한 평가를 덧붙이지 않는다. 사실에 없는 정도 표현(약간, 많이 등)을 붙이지 않는다.\n"
            + TAIL;
    /** 추천 카드: 사실에 있는 제안 하나만 가볍게 권한다. */
    static final String SUGGESTION_SYSTEM = HEAD
            + "   사실에 있는 '제안 내용'과 '제안 시간'만 가볍게 권한다. 다른 조언이나 몸 상태에 대한 평가는 덧붙이지 않는다.\n"
            + "   강요하지 않고 물어보는 말투로 끝낸다.\n"
            + TAIL;

    static String system(String kind) {
        return "SUGGESTION_MESSAGE".equals(kind) ? SUGGESTION_SYSTEM : SYSTEM;
    }

    /** 결과에 있으면 버리는 말(진단·의료 표현). */
    private static final List<String> FORBIDDEN = List.of("진단", "질병", "질환", "치료", "처방", "병원", "약을", "우울증");

    private InterpretationPrompt() {
    }

    static String user(String kind, List<String> facts, String fallbackText) {
        StringBuilder sb = new StringBuilder();
        sb.append("요청: ").append(instruction(kind)).append('\n');
        sb.append("사실:\n");
        for (String fact : facts) {
            sb.append("- ").append(fact).append('\n');
        }
        sb.append("참고 문장(같은 뜻을 더 자연스럽게 써도 된다): ").append(fallbackText);
        return sb.toString();
    }

    private static String instruction(String kind) {
        return switch (kind) {
            case "FOUR_WEEK_SUMMARY" -> "4주 동안의 변화를 한두 문장으로 요약해 줘.";
            case "SUGGESTION_MESSAGE" -> "추천 카드에 넣을 문구를 써 줘. 왜 권하는지와 무엇을 언제 권하는지가 드러나게.";
            default -> "최근 회복 상태를 한두 문장으로 알려 줘.";
        };
    }

    /** 캐시 키: 프롬프트 버전·종류·사실·대체 문구가 같으면 같은 값. */
    static String inputHash(String kind, List<String> facts, String fallbackText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = VERSION + "\n" + kind + "\n" + String.join("\n", facts) + "\n" + fallbackText;
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 모델이 쓴 문장을 다듬고 검증한다. 쓸 수 없으면 비어 있다(호출하는 쪽이 대체 문구를 쓴다).
     * 줄바꿈·겹친 공백을 하나로 줄이고 감싼 따옴표를 뗀 뒤, 비었거나 너무 길거나 금지 표현이 있으면 버린다.
     */
    static Optional<String> clean(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.replaceAll("\\s+", " ").strip();
        while (text.length() >= 2 && isQuote(text.charAt(0)) && isQuote(text.charAt(text.length() - 1))) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        for (String word : FORBIDDEN) {
            if (text.contains(word)) {
                return Optional.empty();
            }
        }
        return Optional.of(text);
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'' || c == '“' || c == '”' || c == '‘' || c == '’';
    }
}
