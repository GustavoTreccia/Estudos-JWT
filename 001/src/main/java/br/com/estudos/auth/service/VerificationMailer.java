package br.com.estudos.auth.service;

import br.com.estudos.auth.config.AuthProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class VerificationMailer {
    private final JavaMailSender sender;
    private final AuthProperties properties;

    public VerificationMailer(JavaMailSender sender, AuthProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    public void send(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("Confirme seu cadastro");
        message.setText("Seu codigo de confirmacao: " + code
                + "\n\nValido por 10 minutos. Se voce nao solicitou o cadastro, ignore este e-mail.");
        sender.send(message);
    }
}
