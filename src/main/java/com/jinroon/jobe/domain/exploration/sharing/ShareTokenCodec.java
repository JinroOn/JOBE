package com.jinroon.jobe.domain.exploration.sharing;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A random nonce + domain-separated server MAC permits replay without storing bearer token plaintext. */
@Component
public class ShareTokenCodec {
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    public ShareTokenCodec(@Value("${jwt.secret}")String secret){key=mac(secret.getBytes(StandardCharsets.UTF_8),"JOBE/exploration/result-share/v1".getBytes(StandardCharsets.UTF_8));}
    public byte[] nonce(){byte[] value=new byte[32];random.nextBytes(value);return value;}
    public String token(byte[] nonce){if(nonce.length!=32)throw new IllegalArgumentException("Invalid share nonce");return "S1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(key,nonce));}
    public boolean validFormat(String token){
        if(token==null||!token.matches("S1_[A-Za-z0-9_-]{43}"))return false;
        byte[] raw=Base64.getUrlDecoder().decode(token.substring(3));return Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(token.substring(3));
    }
    public byte[] hash(String token){try{return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public boolean matches(byte[] nonce,byte[] expected){return MessageDigest.isEqual(hash(token(nonce)),expected);}
    private static byte[] mac(byte[] key,byte[] data){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(data);}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
}
