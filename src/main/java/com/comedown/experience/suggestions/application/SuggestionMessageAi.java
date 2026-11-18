package com.comedown.experience.suggestions.application;

import com.comedown.ai.contract.AiInterpretation;
import com.comedown.recommendation.contract.SuggestionCommands;
import com.comedown.recommendation.contract.SuggestionPayload;
import com.comedown.recommendation.contract.SuggestionPayloads;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 추천 카드 문구를 AI 문구로 바꾼다. 제안은 규칙 기반 문구로 먼저 저장되고(트랜잭션 안), 그 트랜잭션이 끝난 뒤 여기서
 * AI에 물어 성공한 것만 recommendation의 계약으로 바꿔 저장한다: 외부 호출을 DB 트랜잭션에 넣지 않기 위해서다.
 * 실패하면 규칙 기반 문구가 그대로 남고 다음에 다시 시도한다.
 * <p>
 * AI에는 제안 종류·내용·시간·이유만 보낸다. 대비할 일정의 제목(relatedEvent)은 보내지 않는다.
 * 한 번에 최대 {@link #MAX_PER_CALL}건을 동시에 묻는다(응답이 늦어지지 않게).
 */
@Component
public class SuggestionMessageAi {

    static final int MAX_PER_CALL = 3;
    private static final Logger log = LoggerFactory.getLogger(SuggestionMessageAi.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN);

    private final AiInterpretation ai;
    private final SuggestionCommands commands;
    private final SuggestionQuery query;
    private final SuggestionPayloads payloads;
    private final ServiceTime time;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SuggestionMessageAi(AiInterpretation ai, SuggestionCommands commands, SuggestionQuery query,
            SuggestionPayloads payloads, ServiceTime time) {
        this.ai = ai;
        this.commands = commands;
        this.query = query;
        this.payloads = payloads;
        this.time = time;
    }

    /** 아직 규칙 기반 문구인 PROPOSED 제안의 문구를 AI 문구로 바꿔 돌려준다. 순서와 나머지 값은 그대로다. */
    public List<SuggestionPayload> apply(long userId, List<SuggestionPayload> suggestions) {
        Map<Long, CompletableFuture<Optional<String>>> asked = new LinkedHashMap<>();
        for (SuggestionPayload s : suggestions) {
            if (asked.size() < MAX_PER_CALL && needsAi(s)) {
                asked.put(s.id(), CompletableFuture.supplyAsync(() -> write(s), executor));
            }
        }
        if (asked.isEmpty()) {
            return suggestions;
        }
        List<SuggestionPayload> result = new ArrayList<>(suggestions.size());
        for (SuggestionPayload s : suggestions) {
            Optional<String> text = asked.containsKey(s.id()) ? asked.get(s.id()).join() : Optional.empty();
            result.add(text.filter(t -> rewrite(userId, s.id(), t))
                    .map(t -> s.withMessage(new SuggestionPayload.Message(t, true, false)))
                    .orElse(s));
        }
        return result;
    }

    /** 그 종류의 PROPOSED 제안 문구를 바꾼다(응답에 제안을 싣지 않는 흐름용, 예: 동기화 뒤 PRE_START). */
    public void applyToProposed(long userId, String type) {
        try {
            apply(userId, payloads.of(userId, query.search(userId, type, "PROPOSED", null)));
        } catch (RuntimeException e) {
            log.warn("추천 문구 AI 갱신 실패(type={}): {}", type, e.getClass().getSimpleName());
        }
    }

    private static boolean needsAi(SuggestionPayload s) {
        return "PROPOSED".equals(s.status()) && s.message() != null && s.message().fallback();
    }

    private Optional<String> write(SuggestionPayload s) {
        try {
            return ai.write(AiInterpretation.SUGGESTION_MESSAGE, facts(s, time.today()), s.message().text());
        } catch (RuntimeException e) {
            log.warn("추천 문구 AI 생성 실패: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 저장에 성공했는지. 그 사이 상태가 바뀌었거나(승인·건너뛰기) 이미 AI 문구면 바꾸지 않는다. */
    private boolean rewrite(long userId, long suggestionId, String text) {
        try {
            return commands.rewriteMessage(userId, suggestionId, text);
        } catch (RuntimeException e) {
            log.warn("추천 문구 저장 실패: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * AI에 보낼 사실. 날짜·시각은 모델이 계산하지 않게 코드가 화면에 쓸 표현으로 정해 준다(모델은 오늘 날짜를 모른다):
     * 날짜는 오늘·내일이면 "오늘(11월 18일 수요일)"처럼, 그 밖의 날은 "11월 24일 화요일", 시각은 "오전 7시"·"오후 4시 30분".
     */
    static List<String> facts(SuggestionPayload s, LocalDate today) {
        List<String> facts = new ArrayList<>();
        boolean preStart = "PRE_START".equals(s.type());
        ZonedDateTime start = s.start().atZoneSameInstant(ServiceTime.ZONE);
        facts.add("제안 종류: " + (preStart ? "마감 전에 미리 시작하기" : "회복을 위한 가벼운 활동"));
        facts.add("제안 내용: " + s.title() + " (" + s.durationMin() + "분)");
        facts.add("제안 날짜: " + dayLabel(start.toLocalDate(), today));
        facts.add("제안 시각: " + timeLabel(start.toLocalTime()));
        String reason = switch (s.reasonCode() == null ? "" : s.reasonCode()) {
            case "LOW_RECOVERY" -> "오늘 아침 심박변이도(HRV) 측정값이 지난주 평균보다 10% 이상 낮음";
            case "OVERLOAD" -> "이번 주 캘린더 일정이 평소보다 많음";
            case "RISK_WEEK" -> "마감이 몰린 주가 다가옴. 마감일은 바꾸지 않고 준비 시간만 추가함";
            default -> null;
        };
        if (reason != null) {
            facts.add("제안 이유: " + reason);
        }
        if ("OVERLOAD_LOW".equals(s.judgmentState())) {
            facts.add("이번 주 일정도 평소보다 많음");
        }
        return facts;
    }

    static String dayLabel(LocalDate date, LocalDate today) {
        String day = date.format(DAY);
        long days = ChronoUnit.DAYS.between(today, date);
        if (days == 0) {
            return "오늘(" + day + ")";
        }
        if (days == 1) {
            return "내일(" + day + ")";
        }
        return day;
    }

    static String timeLabel(LocalTime t) {
        int hour12 = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        String label = (t.getHour() < 12 ? "오전 " : "오후 ") + hour12 + "시";
        return t.getMinute() == 0 ? label : label + " " + t.getMinute() + "분";
    }
}
