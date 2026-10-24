package br.com.estudos.auth;

import br.com.estudos.auth.config.AuthProperties;
import br.com.estudos.auth.service.VerificationMailer;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationMailerTest {
    @Test
    void deliversConfirmationCodeThroughRealSmtp() throws Exception {
        GreenMail smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.start();
        try {
            var sender = new JavaMailSenderImpl();
            sender.setHost("127.0.0.1");
            sender.setPort(smtp.getSmtp().getPort());
            var unusedKey = new ByteArrayResource(new byte[0]);
            var properties = new AuthProperties("http://localhost:8080", "auth-api", "test-client",
                    "unused-test-client-secret-32-characters", "http://127.0.0.1/callback",
                    "unused-test-code-secret-32-characters", "no-reply@example.test", unusedKey, unusedKey);
            new VerificationMailer(sender, properties).send("person@example.test", "012345");
            assertThat(smtp.waitForIncomingEmail(5000, 1)).isTrue();
            var message = smtp.getReceivedMessages()[0];
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo("person@example.test");
            assertThat(message.getFrom()[0].toString()).isEqualTo("no-reply@example.test");
            assertThat(message.getSubject()).isEqualTo("Confirme seu cadastro");
            assertThat(message.getContent().toString()).contains("012345", "10 minutos");
        } finally {
            smtp.stop();
        }
    }
}
