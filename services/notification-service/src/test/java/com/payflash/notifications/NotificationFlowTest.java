package com.payflash.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Postgres + Kafka; only SMTP is mocked. Events are published as raw JSON, exactly as the core app's
 * outbox publisher sends them, so this test also pins down the event contract.
 */
@SpringBootTest(properties = {"management.tracing.enabled=false", "spring.kafka.admin.auto-create=true"})
@AutoConfigureMockMvc
class NotificationFlowTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    NotificationRepository repository;
    @Autowired
    NotificationService service;
    @Value("${app.jwt.secret}")
    String secret;

    @MockitoBean
    JavaMailSender mailSender;

    private static TransactionEvent.Party party(long id, String name) {
        return new TransactionEvent.Party(id, name.toLowerCase() + id + "@test.dev", name);
    }

    private TransactionEvent transfer(long fromUser, long toUser) {
        return new TransactionEvent(UUID.randomUUID(), "TRANSFER", 10L, 20L, new BigDecimal("125.50"), "INR",
                Instant.now(), party(fromUser, "Asha"), party(toUser, "Ravi"), "dinner");
    }

    private void publish(TransactionEvent event) throws Exception {
        kafka.send(KafkaConfig.TOPIC, event.reference().toString(), mapper.writeValueAsString(event)).get();
    }

    private String token(long userId, String type) {
        var key = new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
        var claims = JwtClaimsSet.builder().subject(String.valueOf(userId)).claim("typ", type).claim("role", "USER")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plus(Duration.ofMinutes(5))).build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void transferEventEmailsBothPartiesExactlyOnce() throws Exception {
        long sender = 1_000 + (long) (Math.random() * 1_000_000);
        var event = transfer(sender, sender + 1);
        publish(event);

        await().atMost(Duration.ofSeconds(30)).until(() -> repository.findByEventReference(event.reference()).size() == 2);
        verify(mailSender).send(argThat((SimpleMailMessage m) -> m.getTo()[0].equals(event.from().email())
                && m.getSubject().equals("You sent ₹125.50 to Ravi")));
        verify(mailSender).send(argThat((SimpleMailMessage m) -> m.getTo()[0].equals(event.to().email())
                && m.getSubject().equals("You received ₹125.50 from Asha")));

        // At-least-once delivery: the same event again sends nothing new.
        clearInvocations(mailSender);
        service.handle(event);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(repository.findByEventReference(event.reference())).hasSize(2);
    }

    @Test
    void paymentRequestsEmailThePersonWhoHasToAct() throws Exception {
        long asha = 6_000_000 + (long) (Math.random() * 1_000_000);
        var requester = party(asha, "Asha");
        var payer = party(asha + 1, "Ravi");
        // Exactly what the core app's outbox publishes on payment.requests.
        var asked = new PaymentRequestEvent(UUID.randomUUID(), "REQUESTED", 77L, new BigDecimal("300.00"), "INR",
                "Dinner", requester, payer, Instant.now());
        var declined = new PaymentRequestEvent(UUID.randomUUID(), "DECLINED", 77L, new BigDecimal("300.00"), "INR",
                "Dinner", requester, payer, Instant.now());
        kafka.send(KafkaConfig.REQUESTS_TOPIC, "77", mapper.writeValueAsString(asked)).get();
        kafka.send(KafkaConfig.REQUESTS_TOPIC, "77", mapper.writeValueAsString(declined)).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> !repository.findByEventReference(declined.reference()).isEmpty());
        verify(mailSender).send(argThat((SimpleMailMessage m) -> m.getTo()[0].equals(payer.email())
                && m.getSubject().equals("Asha requested ₹300.00 from you")
                && m.getText().contains("go to Requests")));
        verify(mailSender).send(argThat((SimpleMailMessage m) -> m.getTo()[0].equals(requester.email())
                && m.getSubject().equals("Ravi declined your request for ₹300.00")));
        assertThat(repository.findByEventReference(asked.reference())).singleElement()
                .satisfies(n -> assertThat(n.getUserId()).isEqualTo(payer.userId()));
    }

    @Test
    void deletingAnAccountDeletesItsNotifications() throws Exception {
        long user = 4_000_000 + (long) (Math.random() * 1_000_000);
        var purchase = new TransactionEvent(UUID.randomUUID(), "PURCHASE", 31L, null, new BigDecimal("10.00"), "INR",
                Instant.now(), party(user, "Kiran"), null, "Flash sale: Mug #1");
        publish(purchase);
        await().atMost(Duration.ofSeconds(30)).until(() -> !repository.findByEventReference(purchase.reference()).isEmpty());

        // Exactly what the core app's outbox publishes when an account is deleted.
        kafka.send(KafkaConfig.ACCOUNTS_TOPIC, String.valueOf(user),
                "{\"userId\":%d,\"deletedAt\":\"%s\"}".formatted(user, Instant.now())).get();
        await().atMost(Duration.ofSeconds(30)).until(() -> repository.findByEventReference(purchase.reference()).isEmpty());
    }

    @Test
    void usersSeeOnlyTheirOwnNotificationsWithAValidAccessToken() throws Exception {
        long buyer = 2_000_000 + (long) (Math.random() * 1_000_000);
        var purchase = new TransactionEvent(UUID.randomUUID(), "PURCHASE", 30L, null, new BigDecimal("99.00"), "INR",
                Instant.now(), party(buyer, "Meera"), null, "Flash sale: Sneakers #7");
        publish(purchase);
        await().atMost(Duration.ofSeconds(30)).until(() -> !repository.findByEventReference(purchase.reference()).isEmpty());

        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + token(buyer, "access")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].subject").value("Payment of ₹99.00 successful"))
                .andExpect(jsonPath("$[0].body").value(org.hamcrest.Matchers.containsString("Flash sale: Sneakers #7")));
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + token(buyer + 1, "access")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Refresh tokens, missing tokens and forged tokens are all rejected.
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + token(buyer, "refresh")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + token(buyer, "access") + "x"))
                .andExpect(status().isUnauthorized());
    }
}
