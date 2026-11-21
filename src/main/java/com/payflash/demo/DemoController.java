package com.payflash.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Demo")
public class DemoController {

    public record DemoLogin(String email, String password, String fullName) {
    }

    public record DemoInfo(boolean enabled, List<DemoLogin> accounts) {
    }

    private final DemoMode demo;

    public DemoController(DemoMode demo) {
        this.demo = demo;
    }

    /** The login page reads this to show "Try the demo" buttons. Empty unless DEMO_MODE=true. */
    @GetMapping("/api/v1/demo")
    @Operation(summary = "Shared demo logins (only in demo mode)")
    public DemoInfo info() {
        return new DemoInfo(demo.enabled(), demo.accounts().stream()
                .map(a -> new DemoLogin(a.email(), demo.password(), a.fullName()))
                .toList());
    }
}
