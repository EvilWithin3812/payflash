package com.payflash.request;

import com.payflash.auth.TransactionPins;
import com.payflash.auth.User;
import com.payflash.auth.UserRepository;
import com.payflash.common.BusinessException;
import com.payflash.outbox.OutboxService;
import com.payflash.outbox.Topics;
import com.payflash.request.RequestDtos.CreateRequest;
import com.payflash.request.RequestDtos.Direction;
import com.payflash.request.RequestDtos.RequestResponse;
import com.payflash.request.RequestDtos.RequestSummary;
import com.payflash.request.RequestDtos.SplitRequest;
import com.payflash.request.RequestDtos.SplitResponse;
import com.payflash.wallet.WalletDtos.Counterparty;
import com.payflash.wallet.WalletEvents.Party;
import com.payflash.wallet.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment requests ("please pay me ₹200") and split bills (one request per friend).
 *
 * <p>Paying a request is an ordinary wallet transfer with an internal idempotency key ({@code request:<id>}),
 * made while the request row is locked: two "Pay" clicks, or "Pay" racing "Decline", can never pay twice.
 */
@Service
public class PaymentRequestService {

    private static final BigDecimal ONE_PAISA = new BigDecimal("0.01");
    private static final String CURRENCY = "INR";

    private final PaymentRequestRepository requests;
    private final UserRepository users;
    private final WalletService wallets;
    private final TransactionPins pins;
    private final OutboxService outbox;
    private final int maxPendingPerRequester;

    public PaymentRequestService(PaymentRequestRepository requests, UserRepository users, WalletService wallets,
                                 TransactionPins pins, OutboxService outbox,
                                 @Value("${app.requests.max-pending:30}") int maxPendingPerRequester) {
        this.requests = requests;
        this.users = users;
        this.wallets = wallets;
        this.pins = pins;
        this.outbox = outbox;
        this.maxPendingPerRequester = maxPendingPerRequester;
    }

    @Transactional
    public RequestResponse request(Long requesterId, CreateRequest req) {
        User requester = activeUser(requesterId);
        User payer = payerByEmail(req.payerEmail(), requester);
        checkPendingLimit(requesterId, 1);
        PaymentRequest saved = requests.save(new PaymentRequest(requesterId, payer.getId(), req.amount(),
                blankToNull(req.note()), null));
        publish("REQUESTED", saved, requester, payer);
        return view(saved, requesterId, payer);
    }

    /**
     * Splits a bill equally and sends one request per friend. Amounts are in paise, so a remainder can't be
     * shared exactly: with {@code includeMe} the requester's own share absorbs it, otherwise the first friends
     * pay one paisa more (₹100 / 3 = 33.34 + 33.33 + 33.33).
     */
    @Transactional
    public SplitResponse split(Long requesterId, SplitRequest req) {
        User requester = activeUser(requesterId);
        Set<String> emails = new LinkedHashSet<>();
        for (String email : req.friendEmails()) {
            emails.add(email.trim().toLowerCase(Locale.ROOT));
        }
        List<User> friends = new ArrayList<>();
        for (String email : emails) {
            friends.add(payerByEmail(email, requester));
        }
        int people = friends.size() + (req.includeMe() ? 1 : 0);
        if (people < 2) {
            throw BusinessException.badRequest("SPLIT_TOO_SMALL", "Add at least one friend to split with, or include yourself");
        }
        BigDecimal total = req.totalAmount();
        BigDecimal share = total.divide(BigDecimal.valueOf(people), 2, RoundingMode.DOWN);
        if (share.compareTo(ONE_PAISA) < 0) {
            throw BusinessException.badRequest("SPLIT_TOO_SMALL", "The amount is too small to split between that many people");
        }
        int extraPaise = total.subtract(share.multiply(BigDecimal.valueOf(people)))
                .movePointRight(2).intValueExact();
        checkPendingLimit(requesterId, friends.size());

        UUID group = UUID.randomUUID();
        String note = blankToNull(req.note());
        List<RequestResponse> created = new ArrayList<>();
        for (int i = 0; i < friends.size(); i++) {
            BigDecimal amount = share;
            if (!req.includeMe() && i < extraPaise) {
                amount = amount.add(ONE_PAISA);
            }
            User friend = friends.get(i);
            PaymentRequest saved = requests.save(new PaymentRequest(requesterId, friend.getId(), amount, note, group));
            publish("REQUESTED", saved, requester, friend);
            created.add(view(saved, requesterId, friend));
        }
        BigDecimal myShare = req.includeMe()
                ? share.add(ONE_PAISA.multiply(BigDecimal.valueOf(extraPaise)))
                : BigDecimal.ZERO.setScale(2);
        return new SplitResponse(group, total, myShare, created);
    }

    /** Pays a request addressed to me. Asks for the PIN above the threshold, like any transfer. */
    @Transactional
    public RequestResponse pay(Long payerId, Long requestId, String pin) {
        PaymentRequest request = lockOwnedBy(requestId, payerId, false);
        if (request.getStatus() != PaymentRequestStatus.PENDING) {
            throw BusinessException.conflict("REQUEST_NOT_PENDING",
                    "This request is already " + request.getStatus().name().toLowerCase(Locale.ROOT));
        }
        pins.verifyFor(payerId, request.getAmount(), pin);
        var result = wallets.payUser(payerId, request.getRequesterId(), request.getAmount(),
                request.getNote() == null ? "Payment request" : request.getNote(), "request:" + request.getId());
        request.markPaid(result.transaction().reference());
        return view(request, payerId, users.findById(request.getRequesterId()).orElse(null));
    }

    @Transactional
    public RequestResponse decline(Long payerId, Long requestId) {
        PaymentRequest request = lockOwnedBy(requestId, payerId, false);
        request.decline();
        User requester = users.findById(request.getRequesterId()).orElseThrow();
        publish("DECLINED", request, requester, users.findById(payerId).orElseThrow());
        return view(request, payerId, requester);
    }

    @Transactional
    public RequestResponse cancel(Long requesterId, Long requestId) {
        PaymentRequest request = lockOwnedBy(requestId, requesterId, true);
        request.cancel();
        User payer = users.findById(request.getPayerId()).orElseThrow();
        publish("CANCELLED", request, users.findById(requesterId).orElseThrow(), payer);
        return view(request, requesterId, payer);
    }

    @Transactional(readOnly = true)
    public Page<RequestResponse> list(Long userId, Direction direction, int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<PaymentRequest> results = direction == Direction.INCOMING
                ? requests.findByPayerId(userId, pageable)
                : requests.findByRequesterId(userId, pageable);
        // One query for everyone on the other side of this page (no N+1).
        Set<Long> others = results.stream().map(r -> otherSideOf(r, userId)).collect(Collectors.toSet());
        Map<Long, User> byId = users.findAllById(others).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return results.map(r -> view(r, userId, byId.get(otherSideOf(r, userId))));
    }

    @Transactional(readOnly = true)
    public RequestSummary summary(Long userId) {
        return new RequestSummary(
                requests.countByPayerIdAndStatus(userId, PaymentRequestStatus.PENDING),
                requests.countByRequesterIdAndStatus(userId, PaymentRequestStatus.PENDING));
    }

    // ------------------------------------------------------------------ helpers

    private User activeUser(Long userId) {
        return users.findById(userId).filter(u -> !u.isDeleted())
                .orElseThrow(() -> BusinessException.notFound("User not found"));
    }

    private User payerByEmail(String email, User requester) {
        User payer = users.findByEmailIgnoreCase(email.trim()).filter(u -> !u.isDeleted())
                .orElseThrow(() -> BusinessException.notFound("No PayFlash account for " + email.trim()));
        if (payer.getId().equals(requester.getId())) {
            throw BusinessException.badRequest("SELF_REQUEST", "You can't request money from yourself");
        }
        return payer;
    }

    /** Stops one account from flooding people with requests. */
    private void checkPendingLimit(Long requesterId, int adding) {
        long pending = requests.countByRequesterIdAndStatus(requesterId, PaymentRequestStatus.PENDING);
        if (pending + adding > maxPendingPerRequester) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_PENDING_REQUESTS",
                    "You have " + pending + " open requests. Cancel some or wait for people to answer");
        }
    }

    /** Locks the request; someone who isn't on the right side of it gets "not found", not "forbidden". */
    private PaymentRequest lockOwnedBy(Long requestId, Long userId, boolean asRequester) {
        return requests.lockById(requestId)
                .filter(r -> userId.equals(asRequester ? r.getRequesterId() : r.getPayerId()))
                .orElseThrow(() -> BusinessException.notFound("Request not found"));
    }

    private static Long otherSideOf(PaymentRequest r, Long userId) {
        return userId.equals(r.getPayerId()) ? r.getRequesterId() : r.getPayerId();
    }

    private static RequestResponse view(PaymentRequest r, Long viewerId, User other) {
        Direction direction = viewerId.equals(r.getPayerId()) ? Direction.INCOMING : Direction.OUTGOING;
        Counterparty party = other == null ? null
                : other.isDeleted() ? new Counterparty("Deleted user", null, true)
                : new Counterparty(other.getFullName(), other.getEmail(), false);
        return new RequestResponse(r.getId(), direction, r.getStatus(), r.getAmount(), r.getNote(), party,
                r.getSplitGroup(), r.getTransactionReference(), r.getCreatedAt(), r.getResolvedAt());
    }

    private void publish(String kind, PaymentRequest r, User requester, User payer) {
        var event = new RequestEvents.PaymentRequestEvent(UUID.randomUUID(), kind, r.getId(), r.getAmount(),
                CURRENCY, r.getNote(), party(requester), party(payer), Instant.now());
        outbox.record("PaymentRequest", String.valueOf(r.getId()), "PaymentRequest" + capitalised(kind),
                Topics.PAYMENT_REQUESTS, event);
    }

    private static Party party(User u) {
        return new Party(u.getId(), u.getEmail(), u.getFullName());
    }

    private static String capitalised(String kind) {
        return kind.charAt(0) + kind.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
