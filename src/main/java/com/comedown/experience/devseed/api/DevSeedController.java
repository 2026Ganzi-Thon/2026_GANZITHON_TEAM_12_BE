package com.comedown.experience.devseed.api;

import com.comedown.experience.devseed.application.DemoScenario;
import com.comedown.experience.devseed.application.DemoSeedFlow;
import com.comedown.experience.devseed.application.DemoSeedResult;
import com.comedown.shared.error.ErrorResponse.FieldError;
import com.comedown.shared.error.FieldValidationException;
import com.comedown.shared.security.CurrentUser;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/dev/seed} → 201. local 프로필 전용 시연 데이터 입력. 로그인한 사용자({@code POST /api/dev/login})에게 넣는다.
 * 운영 프로필에서는 빈이 만들어지지 않는다.
 */
@RestController
@Profile("local")
public class DevSeedController {

    private static final String SUPPORTED = Arrays.stream(DemoScenario.values())
            .map(Enum::name)
            .collect(Collectors.joining(", "));

    private final DemoSeedFlow flow;

    public DevSeedController(DemoSeedFlow flow) {
        this.flow = flow;
    }

    record DevSeedRequest(String scenario) {
    }

    @PostMapping("/api/dev/seed")
    @ResponseStatus(HttpStatus.CREATED)
    public DemoSeedResult seed(CurrentUser user, @RequestBody DevSeedRequest body) {
        return flow.seed(user.id(), scenario(body.scenario()));
    }

    private static DemoScenario scenario(String value) {
        if (value != null) {
            for (DemoScenario scenario : DemoScenario.values()) {
                if (scenario.name().equals(value)) {
                    return scenario;
                }
            }
        }
        throw new FieldValidationException(List.of(new FieldError("scenario", SUPPORTED + " 중 하나여야 해요.")));
    }
}
