package com.jinroon.jobe.global.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 로컬 메일 확인용 mailpit 프로필. 인증·TLS 없이 로컬 Mailpit(1025)으로 보내고,
 * 보내는 주소는 SMTP 계정이 아니라 app.mail.from-address를 쓴다.
 */
@SpringBootTest
@ActiveProfiles("mailpit")
class MailpitProfileTest {

    @Autowired
    private JavaMailSenderImpl mailSender;

    @Autowired
    private GmailMailService mailService;

    @Test
    void mailIsSentToLocalMailpitWithoutAuthentication() {
        assertThat(mailSender.getHost()).isEqualTo("localhost");
        assertThat(mailSender.getPort()).isEqualTo(1025);
        assertThat(mailSender.getUsername()).isNullOrEmpty();
        assertThat(mailSender.getJavaMailProperties().getProperty("mail.smtp.auth")).isEqualTo("false");
        assertThat(mailSender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
    }

    @Test
    void senderAddressComesFromFromAddressSetting() {
        assertThat(ReflectionTestUtils.getField(mailService, "fromEmail")).isEqualTo("no-reply@jinroon.local");
    }

    @Test
    void mailpitCannotBeCombinedWithProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "mailpit");
        assertThatThrownBy(() -> new MailpitProfileGuard(environment))
                .isInstanceOf(IllegalStateException.class);

        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("dev", "mailpit");
        new MailpitProfileGuard(local);
    }
}
