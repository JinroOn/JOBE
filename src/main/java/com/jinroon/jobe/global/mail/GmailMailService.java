package com.jinroon.jobe.global.mail;

import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailMailService implements MailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.mail.from-name:JOBE}")
    private String fromName;

    @Override
    public void sendEmailVerification(String recipient, String token) {
        sendHtml(
                recipient,
                "[JOBE] 이메일 인증번호 안내",
                verificationTemplate(token)
        );
    }

    @Override
    public void sendPasswordReset(String recipient, String token) {
        sendHtml(
                recipient,
                "[JOBE] 비밀번호 재설정 인증번호 안내",
                passwordResetTemplate(token)
        );
    }

    private void sendHtml(String recipient, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromEmail, fromName);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException exception) {
            log.error("Mail delivery failed recipient={} subject={}", recipient, subject, exception);
            throw new CustomException(ErrorCode.MAIL_SEND_FAILED);
        }
    }

    private String verificationTemplate(String verificationCode) {
        return """
                <!doctype html>
                <html lang="ko">
                <body style="font-family:Arial,sans-serif;color:#1f2937;line-height:1.6">
                  <h2>JOBE 이메일 인증번호</h2>
                  <p>아래 인증번호를 웹 화면에 입력해주세요.</p>
                  <div style="display:inline-block;padding:14px 24px;background:#e6f4f1;color:#0f766e;border-radius:8px;font-size:30px;font-weight:700;letter-spacing:8px">
                    %s
                  </div>
                  <p>이 인증번호는 30분 동안 유효하며 한 번만 사용할 수 있습니다.</p>
                </body>
                </html>
                """.formatted(verificationCode);
    }

    private String passwordResetTemplate(String verificationCode) {
        return """
                <!doctype html>
                <html lang="ko">
                <body style="font-family:Arial,sans-serif;color:#1f2937;line-height:1.6">
                  <h2>JOBE 비밀번호 재설정 인증번호</h2>
                  <p>아래 인증번호를 비밀번호 재설정 화면에 입력해주세요.</p>
                  <div style="display:inline-block;padding:14px 24px;background:#e6f4f1;color:#0f766e;border-radius:8px;font-size:30px;font-weight:700;letter-spacing:8px">
                    %s
                  </div>
                  <p>이 인증번호는 30분 동안 유효하며 한 번만 사용할 수 있습니다.</p>
                  <p>본인이 요청하지 않았다면 이 메일을 무시해주세요.</p>
                </body>
                </html>
                """.formatted(verificationCode);
    }
}
