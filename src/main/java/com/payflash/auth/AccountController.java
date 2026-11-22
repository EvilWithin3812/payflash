package com.payflash.auth;

import com.payflash.auth.AuthDtos.DeleteAccountRequest;
import com.payflash.auth.AuthDtos.MeResponse;
import com.payflash.auth.AuthDtos.SetPinRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Account")
public class AccountController {

    private final AccountService accounts;
    private final AuthService auth;
    private final TransactionPins pins;

    public AccountController(AccountService accounts, AuthService auth, TransactionPins pins) {
        this.accounts = accounts;
        this.auth = auth;
        this.pins = pins;
    }

    @PutMapping("/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set or change my transaction PIN (4-6 digits; asks for the account password)")
    public void setPin(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody SetPinRequest request) {
        pins.set(user.id(), request.pin(), request.password());
    }

    @GetMapping
    @Operation(summary = "My profile, including whether my email is verified")
    public MeResponse me(@AuthenticationPrincipal AuthUser user) {
        return accounts.me(user.id());
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Log out on every device")
    public void logoutEverywhere(@AuthenticationPrincipal AuthUser user) {
        auth.logoutEverywhere(user.id());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete my account (wallet must be empty; asks for the password again)")
    public void delete(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody DeleteAccountRequest request) {
        accounts.delete(user.id(), request.password());
    }
}
