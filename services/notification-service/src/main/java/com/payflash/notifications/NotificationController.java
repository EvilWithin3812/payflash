package com.payflash.notifications;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** The caller's 50 most recent notifications. The user id comes from the verified JWT subject. */
    @GetMapping
    public List<NotificationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return notifications.recentFor(Long.parseLong(jwt.getSubject())).stream()
                .map(n -> new NotificationResponse(n.getId(), n.getEventReference(), n.getChannel(), n.getSubject(),
                        n.getBody(), n.getCreatedAt()))
                .toList();
    }

    public record NotificationResponse(Long id, UUID eventReference, String channel, String subject, String body,
                                       Instant createdAt) {
    }
}
