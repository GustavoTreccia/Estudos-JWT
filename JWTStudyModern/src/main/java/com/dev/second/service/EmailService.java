package com.dev.second.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailService(JavaMailSender mailSender, @Value("${app.auth.email-from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendConfirmationEmail(String recipient, String confirmationUrl) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject("Confirme seu cadastro");
        message.setText("Confirme seu e-mail acessando o link abaixo:\n\n" + confirmationUrl);
        mailSender.send(message);
    }
}
