package com.comedown.cbi.application;

import com.comedown.cbi.contract.CbiCommands;
import com.comedown.cbi.contract.CbiQuery;
import com.comedown.cbi.contract.CbiResult;
import com.comedown.cbi.contract.SubmitCbi;
import com.comedown.cbi.domain.CbiAnswer;
import com.comedown.cbi.domain.CbiAssessment;
import com.comedown.cbi.domain.CbiQuestionnaire;
import com.comedown.cbi.domain.CbiRound;
import com.comedown.cbi.infrastructure.persistence.CbiAnswerRepository;
import com.comedown.cbi.infrastructure.persistence.CbiAssessmentRepository;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CBI 채점·저장·조회. */
@Service
public class CbiService implements CbiCommands, CbiQuery {

    private final CbiAssessmentRepository assessments;
    private final CbiAnswerRepository answers;
    private final ServiceTime serviceTime;

    CbiService(CbiAssessmentRepository assessments, CbiAnswerRepository answers, ServiceTime serviceTime) {
        this.assessments = assessments;
        this.answers = answers;
        this.serviceTime = serviceTime;
    }

    @Override
    @Transactional
    public CbiResult submit(SubmitCbi command) {
        CbiRound round = CbiRound.valueOf(command.round());
        if (!round.supported()) {
            throw new IllegalArgumentException("지원하지 않는 회차입니다: " + round);
        }
        if (assessments.existsByUserIdAndRound(command.userId(), round)) {
            throw new ApiException(ErrorCode.CBI_ALREADY_TAKEN);
        }
        CbiQuestionnaire.Score score = CbiQuestionnaire.score(command.options());
        CbiAssessment saved;
        try {
            saved = assessments.saveAndFlush(CbiAssessment.of(command.userId(), round, score, serviceTime.now()));
        } catch (DataIntegrityViolationException duplicate) {
            // UK(user_id, round): 동시에 들어온 같은 회차 제출
            throw new ApiException(ErrorCode.CBI_ALREADY_TAKEN);
        }
        answers.saveAll(command.options().entrySet().stream()
                .map(e -> CbiAnswer.of(saved.getId(), e.getKey(), e.getValue()))
                .toList());
        return toResult(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CbiResult> findHistory(long userId) {
        return assessments.findByUserIdOrderByTakenAtDescIdDesc(userId).stream().map(CbiService::toResult).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CbiResult> findRound(long userId, String round) {
        return assessments.findByUserIdAndRound(userId, CbiRound.valueOf(round)).map(CbiService::toResult);
    }

    private static CbiResult toResult(CbiAssessment a) {
        return new CbiResult(
                a.getId(), a.getRound().name(), a.getQuestionnaireVersion(), a.getTakenAt(),
                a.getPersonalScore(), CbiQuestionnaire.level(a.getPersonalScore()).name(),
                a.getWorkScore(), CbiQuestionnaire.level(a.getWorkScore()).name(),
                a.isHighRisk());
    }
}
