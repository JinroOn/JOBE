package com.jinroon.jobe.domain.user.controller;

import com.jinroon.jobe.domain.auth.dto.response.EmailVerificationResponse;
import com.jinroon.jobe.domain.user.controller.api.UserApi;
import com.jinroon.jobe.domain.user.dto.response.UserConsentResponse;
import com.jinroon.jobe.domain.user.dto.response.UserFavoriteResponse;
import com.jinroon.jobe.domain.user.dto.response.UserResponse;
import com.jinroon.jobe.domain.user.dto.response.UserSessionResponse;
import com.jinroon.jobe.domain.user.service.UserService;
import com.jinroon.jobe.global.security.CustomUserDetails;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController implements UserApi {

    private final UserService userService;

    @Override
    @GetMapping
    public List<UserResponse> findUsers(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return userService.findUsers().stream()
                .map(UserResponse::from)
                .toList();
    }

    @Override
    @GetMapping("/me")
    public UserResponse getUser(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return UserResponse.from(userService.getUser(userDetails.getUserId()));
    }

    @Override
    @GetMapping("/me/consents")
    public List<UserConsentResponse> findConsents(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return userService.findConsents(userDetails.getUserId()).stream()
                .map(UserConsentResponse::from)
                .toList();
    }

    @Override
    @GetMapping("/me/favorites")
    public List<UserFavoriteResponse> findFavorites(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return userService.findFavorites(userDetails.getUserId()).stream()
                .map(UserFavoriteResponse::from)
                .toList();
    }

    @Override
    @GetMapping("/me/sessions")
    public List<UserSessionResponse> findSessions(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return userService.findSessions(userDetails.getUserId()).stream()
                .map(UserSessionResponse::from)
                .toList();
    }

    @Override
    @GetMapping("/email-verifications/{verificationId}")
    public EmailVerificationResponse getEmailVerification(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long verificationId) {
        return EmailVerificationResponse.from(
                userService.getEmailVerificationForUser(verificationId, userDetails.getUserId()));
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@RequestBody Map<String, Object> request) {
        return UserResponse.from(userService.createUser(request));
    }

    @Override
    @PatchMapping("/me")
    public UserResponse updateUser(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        return UserResponse.from(userService.updateUser(userDetails.getUserId(), request));
    }

    @Override
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@AuthenticationPrincipal CustomUserDetails userDetails) {
        userService.deleteUser(userDetails.getUserId());
    }

    @Override
    @PostMapping("/consents")
    @ResponseStatus(HttpStatus.CREATED)
    public UserConsentResponse createConsent(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        request.put("userId", userDetails.getUserId());
        return UserConsentResponse.from(userService.createConsent(request));
    }

    @Override
    @PostMapping("/favorites")
    @ResponseStatus(HttpStatus.CREATED)
    public UserFavoriteResponse createFavorite(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        request.put("userId", userDetails.getUserId());
        return UserFavoriteResponse.from(userService.createFavorite(request));
    }

    @Override
    @DeleteMapping("/favorites/{favoriteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFavorite(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long favoriteId) {
        userService.deleteFavoriteForUser(favoriteId, userDetails.getUserId());
    }

    @Override
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public UserSessionResponse createSession(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        return UserSessionResponse.from(userService.createSessionForUser(request, userDetails.getUserId()));
    }

    @Override
    @PostMapping("/email-verifications")
    @ResponseStatus(HttpStatus.CREATED)
    public EmailVerificationResponse createEmailVerification(@RequestBody Map<String, Object> request) {
        return EmailVerificationResponse.from(userService.createEmailVerification(request));
    }
}
