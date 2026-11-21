package com.payflash.auth;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends account-security emails: email verification, password reset, password changed, account deleted.
 *
 * <p>These are sent by the core app itself, not through Kafka and the notification service, so that the
 * one-time links never sit in the outbox table or a Kafka log. Only a hash of each token is ever stored.
 *
 * <p>Emails go out after the database transaction commits (a rolled-back signup sends nothing) and on a
 * virtual thread, so a slow SMTP server never slows down the API. If a send fails, the user can ask again
 * ("resend verification email" / "forgot password").
 */
@Component
public class AccountMailer {

    private static final Logger log = LoggerFactory.getLogger(AccountMailer.class);

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;
    private final String publicUrl;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AccountMailer(JavaMailSender mailSender,
                         @Value("${app.mail.enabled:true}") boolean enabled,
                         @Value("${app.mail.from:PayFlash <no-reply@payflash.local>}") String from,
                         @Value("${app.public-url:http://localhost:3000}") String publicUrl) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    // The token goes after '#', not in the query string: browsers never send the fragment to a server,
    // so the token cannot end up in access logs, proxies or Referer headers.

    public void sendVerification(String to, String name, String token) {
        send(to, "Confirm your PayFlash email address", """
                Hi %s,

                Please confirm your email address to start adding money, sending money and buying in flash sales:

                %s/verify-email#token=%s

                The link is valid for 24 hours. If you didn't create a PayFlash account, you can ignore this email.
                """.formatted(name, publicUrl, token));
    }

    public void sendPasswordReset(String to, String name, String token) {
        send(to, "Reset your PayFlash password", """
                Hi %s,

                Someone (hopefully you) asked to reset your PayFlash password. Choose a new one here:

                %s/reset-password#token=%s

                The link is valid for 30 minutes and works once. If you didn't ask for this, ignore this email:
                your password stays the same.
                """.formatted(name, publicUrl, token));
    }

    public void sendPasswordChanged(String to, String name) {
        send(to, "Your PayFlash password was changed", """
                Hi %s,

                Your PayFlash password was just changed, and every device was logged out.

                If this wasn't you, reset your password right away at %s/forgot-password and contact support.
                """.formatted(name, publicUrl));
    }

    public void sendAccountDeleted(String to, String name) {
        send(to, "Your PayFlash account was deleted", """
                Hi %s,

                Your PayFlash account has been deleted, as you asked. Your name and email address have been removed.
                Records of past payments are kept only as long as the law requires (see our privacy policy).

                Thanks for trying PayFlash.
                """.formatted(name));
    }

    private void send(String to, String subject, String body) {
        Runnable task = () -> executor.execute(() -> deliver(to, subject, body));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    void deliver(String to, String subject, String body) {
        if (!enabled) {
            log.info("Email disabled (app.mail.enabled=false); not sending \"{}\"", subject);
            return;
        }
        try {
            var message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (RuntimeException e) {
            // Never log the body: it contains a one-time link.
            log.warn("Could not send \"{}\" email: {}", subject, e.getMessage());
        }
    }
}
