package com.payflash.notifications;

import com.payflash.notifications.TransactionEvent.Party;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Turns a completed transaction into emails for the people involved, at most once per person. */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Locale INDIA = Locale.of("en", "IN");

    private final NotificationRepository repository;
    private final JavaMailSender mailSender;
    private final MeterRegistry metrics;
    private final String from;

    public NotificationService(NotificationRepository repository, JavaMailSender mailSender, MeterRegistry metrics,
                               @Value("${app.notifications.from:PayFlash <no-reply@payflash.local>}") String from) {
        this.repository = repository;
        this.mailSender = mailSender;
        this.metrics = metrics;
        this.from = from;
    }

    public void handle(TransactionEvent event) {
        for (Email email : emailsFor(event)) {
            deliverOnce(event, email);
        }
    }

    public List<Notification> recentFor(long userId) {
        return repository.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 50));
    }

    private void deliverOnce(TransactionEvent event, Email email) {
        Party to = email.to();
        if (repository.existsByEventReferenceAndUserIdAndChannel(event.reference(), to.userId(),
                Notification.CHANNEL_EMAIL)) {
            metrics.counter("payflash.notifications.duplicates").increment();
            log.debug("Duplicate event {} for user {}; already notified", event.reference(), to.userId());
            return;
        }
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to.email());
        message.setSubject(email.subject());
        message.setText(email.body());
        // Send first, then record. If recording fails the event is retried and the email may repeat:
        // a duplicate email is acceptable, a silently missing one is not (at-least-once).
        mailSender.send(message);
        try {
            repository.saveAndFlush(new Notification(event.reference(), to.userId(), Notification.CHANNEL_EMAIL,
                    to.email(), email.subject(), email.body()));
            metrics.counter("payflash.notifications.sent", "type", String.valueOf(event.type())).increment();
        } catch (DataIntegrityViolationException raced) {
            log.debug("Another consumer recorded event {} for user {} first", event.reference(), to.userId());
        }
    }

    private List<Email> emailsFor(TransactionEvent event) {
        String amount = format(event.amount(), event.currency());
        List<Email> emails = new ArrayList<>(2);
        switch (String.valueOf(event.type())) {
            case "TRANSFER" -> {
                Party sender = require(event.from(), event);
                Party receiver = require(event.to(), event);
                emails.add(new Email(sender, "You sent " + amount + " to " + receiver.fullName(),
                        body(sender, "You sent " + amount + " to " + receiver.fullName() + " (" + receiver.email() + ").", event)));
                emails.add(new Email(receiver, "You received " + amount + " from " + sender.fullName(),
                        body(receiver, sender.fullName() + " (" + sender.email() + ") sent you " + amount + ".", event)));
            }
            case "PURCHASE" -> {
                Party buyer = require(event.from(), event);
                emails.add(new Email(buyer, "Payment of " + amount + " successful",
                        body(buyer, "You paid " + amount + " from your PayFlash wallet"
                                + (event.note() == null ? "." : " for " + event.note() + "."), event)));
            }
            case "TOPUP" -> {
                Party owner = require(event.to(), event);
                emails.add(new Email(owner, amount + " added to your PayFlash wallet",
                        body(owner, amount + " was added to your wallet.", event)));
            }
            default -> log.info("No notification for transaction type {}", event.type());
        }
        return emails;
    }

    /** A party without contact details is a contract violation: fail so it is retried, then dead-lettered. */
    private static Party require(Party party, TransactionEvent event) {
        if (party == null || party.userId() == null || party.email() == null) {
            throw new IllegalStateException("Event " + event.reference() + " is missing a party for " + event.type());
        }
        return party;
    }

    private static String body(Party to, String line, TransactionEvent event) {
        return """
                Hi %s,

                %s

                Reference: %s

                - PayFlash
                """.formatted(to.fullName(), line, event.reference());
    }

    static String format(BigDecimal amount, String currencyCode) {
        NumberFormat fmt = NumberFormat.getCurrencyInstance(INDIA);
        fmt.setCurrency(Currency.getInstance(currencyCode == null ? "INR" : currencyCode));
        return fmt.format(amount);
    }

    private record Email(Party to, String subject, String body) {
    }
}
