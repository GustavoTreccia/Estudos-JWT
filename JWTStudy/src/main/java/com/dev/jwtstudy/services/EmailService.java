package com.dev.jwtstudy.services;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
	
	private final JavaMailSender mailSender;
	
	public EmailService(JavaMailSender mailSender) {
		this.mailSender = mailSender;
	}

	public void sendConfirmationEmail(String to, String token) {

        String link = "http://localhost:8080/auth/confirm?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("Confirmação de cadastro");
        message.setText(
            "Clique no link para confirmar sua conta:\n\n" + link
        );

        mailSender.send(message);
    }

	
}
