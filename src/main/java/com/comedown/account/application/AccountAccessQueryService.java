package com.comedown.account.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.account.infrastructure.persistence.AccountAccessReader;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 계정 상태 읽기 계약 구현. 쓰기는 하지 않는다.
 * 동의 판단: SENSITIVE_HRV의 가장 최근 동의 행(agreed_at, id 순)이 철회되지 않았으면 유효.
 */
@Service
public class AccountAccessQueryService implements AccountAccessQuery {

    private final AccountAccessReader reader;

    public AccountAccessQueryService(AccountAccessReader reader) {
        this.reader = reader;
    }

    @Override
    public Optional<HealthDataAccess> findHealthDataAccess(long userId) {
        return reader.find(userId);
    }

    @Override
    public Optional<String> findNickname(long userId) {
        return reader.findNickname(userId);
    }
}
