package com.comedown.cbi.infrastructure.persistence;

import com.comedown.cbi.domain.CbiAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CbiAnswerRepository extends JpaRepository<CbiAnswer, CbiAnswer.Key> {
}
