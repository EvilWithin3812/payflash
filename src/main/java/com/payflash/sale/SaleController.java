package com.payflash.sale;

import com.payflash.auth.AuthUser;
import com.payflash.sale.SaleDtos.SaleOrderResponse;
import com.payflash.sale.SaleDtos.SaleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sales")
@Tag(name = "Flash sales")
public class SaleController {

    private final SaleService sales;

    public SaleController(SaleService sales) {
        this.sales = sales;
    }

    @GetMapping
    @Operation(summary = "Upcoming and live sales with units left (public)")
    public List<SaleResponse> list() {
        return sales.listUpcomingAndLive();
    }

    @GetMapping("/{saleId}")
    @Operation(summary = "One sale with units left (public). Live updates: STOMP /ws, topic /topic/sales/{id}")
    public SaleResponse get(@PathVariable long saleId) {
        return sales.get(saleId);
    }

    @PostMapping("/{saleId}/reserve")
    @Operation(summary = "Grab a unit. 202 = held for you; pay before expiresAt. 409 = sold out / already reserved")
    public ResponseEntity<SaleOrderResponse> reserve(@AuthenticationPrincipal AuthUser user, @PathVariable long saleId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(sales.reserve(user.id(), saleId));
    }

    @GetMapping("/orders")
    @Operation(summary = "My flash-sale orders, newest first")
    public List<SaleOrderResponse> myOrders(@AuthenticationPrincipal AuthUser user) {
        return sales.myOrders(user.id());
    }

    @GetMapping("/orders/{holdToken}")
    @Operation(summary = "Status of one reservation: PROCESSING -> HELD -> PAID (or EXPIRED)")
    public SaleOrderResponse order(@AuthenticationPrincipal AuthUser user, @PathVariable UUID holdToken) {
        return sales.getOrder(user.id(), holdToken);
    }

    @PostMapping("/orders/{holdToken}/pay")
    @Operation(summary = "Pay a held unit from my wallet (safe to retry)")
    public SaleOrderResponse pay(@AuthenticationPrincipal AuthUser user, @PathVariable UUID holdToken) {
        return sales.pay(user.id(), holdToken);
    }
}
