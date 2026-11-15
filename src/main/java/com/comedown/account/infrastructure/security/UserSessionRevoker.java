package com.comedown.account.infrastructure.security;

import com.comedown.account.contract.UserSessions;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Spring Session의 PRINCIPAL_NAME 색인(= users.id)으로 사용자의 모든 세션을 지운다. */
@Component
class UserSessionRevoker implements UserSessions {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    UserSessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    @Override
    public int revokeAll(long userId) {
        var found = sessions.findByPrincipalName(String.valueOf(userId));
        found.keySet().forEach(sessions::deleteById);
        return found.size();
    }
}
