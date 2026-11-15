package com.comedown.experience.home.api;

import com.comedown.experience.home.application.HomeQuery;
import com.comedown.experience.home.application.HomeView;
import com.comedown.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/home} → 200. 홈(B1)에 필요한 값을 한 번에 준다(HTTP 책임: experience.home). */
@RestController
public class HomeController {

    private final HomeQuery homeQuery;

    public HomeController(HomeQuery homeQuery) {
        this.homeQuery = homeQuery;
    }

    @GetMapping("/api/home")
    public HomeView home(CurrentUser user) {
        return homeQuery.home(user.id());
    }
}
