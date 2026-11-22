package com.payflash.request;

import com.payflash.auth.AuthUser;
import com.payflash.request.RequestDtos.CreateRequest;
import com.payflash.request.RequestDtos.Direction;
import com.payflash.request.RequestDtos.PayRequest;
import com.payflash.request.RequestDtos.RequestResponse;
import com.payflash.request.RequestDtos.RequestSummary;
import com.payflash.request.RequestDtos.SplitRequest;
import com.payflash.request.RequestDtos.SplitResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/requests")
@Tag(name = "Payment requests")
public class PaymentRequestController {

    private final PaymentRequestService service;

    public PaymentRequestController(PaymentRequestService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask someone to pay me")
    public RequestResponse request(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateRequest request) {
        return service.request(user.id(), request);
    }

    @PostMapping("/split")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Split a bill equally and send each friend a request")
    public SplitResponse split(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody SplitRequest request) {
        return service.split(user.id(), request);
    }

    @GetMapping
    @Operation(summary = "Requests to me (direction=INCOMING) or from me (OUTGOING), newest first")
    public PagedModel<RequestResponse> list(@AuthenticationPrincipal AuthUser user,
                                            @RequestParam(defaultValue = "INCOMING") Direction direction,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return new PagedModel<>(service.list(user.id(), direction, page, size));
    }

    @GetMapping("/summary")
    @Operation(summary = "How many requests are waiting for me, and for others")
    public RequestSummary summary(@AuthenticationPrincipal AuthUser user) {
        return service.summary(user.id());
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Pay a request sent to me (send the PIN when the amount is above the PIN threshold)")
    public RequestResponse pay(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                               @Valid @RequestBody(required = false) PayRequest body) {
        return service.pay(user.id(), id, body == null ? null : body.pin());
    }

    @PostMapping("/{id}/decline")
    @Operation(summary = "Decline a request sent to me")
    public RequestResponse decline(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return service.decline(user.id(), id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a request I sent")
    public RequestResponse cancel(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return service.cancel(user.id(), id);
    }
}
