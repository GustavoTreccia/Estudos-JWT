package br.com.estudos.auth.controller;

import br.com.estudos.auth.record.ConfirmationRequest;
import br.com.estudos.auth.record.EmailRequest;
import br.com.estudos.auth.record.MessageResponse;
import br.com.estudos.auth.record.RegistrationRequest;
import br.com.estudos.auth.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse register(@Valid @RequestBody RegistrationRequest request) {
        accounts.register(request.email(), request.password());
        return accepted();
    }

    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse resend(@Valid @RequestBody EmailRequest request) {
        accounts.resend(request.email());
        return accepted();
    }

    @PostMapping("/confirm")
    public MessageResponse confirm(@Valid @RequestBody ConfirmationRequest request) {
        accounts.confirm(request.email(), request.code());
        return new MessageResponse("Cadastro confirmado. Voce ja pode entrar.");
    }

    private MessageResponse accepted() {
        return new MessageResponse("Se o cadastro estiver elegivel, um codigo sera enviado ao e-mail informado.");
    }
}
