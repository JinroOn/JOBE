package com.jinroon.jobe.global.mail;

public interface MailService {

    void sendEmailVerification(String recipient, String token);

    void sendPasswordReset(String recipient, String token);
}
