package com.jinroon.jobe.domain.user.controller;

import com.jinroon.jobe.domain.auth.dto.response.EmailVerificationResponse;
import com.jinroon.jobe.domain.user.controller.api.UserApi;
import com.jinroon.jobe.domain.user.dto.response.UserConsentResponse;
import com.jinroon.jobe.domain.user.dto.response.UserFavoriteResponse;
import com.jinroon.jobe.domain.user.dto.response.UserResponse;
import com.jinroon.jobe.domain.user.dto.response.UserSessionResponse;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.service.UserService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    public List<User> findUsers() {
        return userService.findUsers();
    }

    @Override
    @GetMapping("/{userId}")
    public UserResponse getUser(@PathVariable Long userId) {
        return UserResponse.from(userService.getUser(userId));
    }

    @Override
    @GetMapping("/{userId}/consents")
    public List<UserConsentResponse> findConsents(@PathVariable Long userId) {
        return userService.findConsents(userId).stream()
                .map(UserConsentResponse::from)
                .toList();
    }

    @Override
    @GetMapping("/{userId}/favorites")
    public List<UserFavoriteResponse> findFavorites(@PathVariable Long userId) {
        return userService.findFavorites(userId).stream()
                .map(UserFavoriteResponse::from)
                .toList();
    }

    @Override
    @GetMapping("/{userId}/sessions")
    public List<UserSessionResponse> findSessions(@PathVariable Long userId) {
        return userService.findSessions(userId).stream()
                .map(UserSessionResponse::from)
                .toList();
    }

    @Override
    @GetMapping("/email-verifications/{verificationId}")
    public EmailVerificationResponse getEmailVerification(@PathVariable Long verificationId) {
        return EmailVerificationResponse.from(userService.getEmailVerification(verificationId));
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@RequestBody Map<String, Object> request) {
        return UserResponse.from(userService.createUser(request));
    }

    @Override
    @PatchMapping("/{userId}")
    public UserResponse updateUser(@PathVariable Long userId, @RequestBody Map<String, Object> request) {
        return UserResponse.from(userService.updateUser(userId, request));
    }

    @Override
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable Long userId) {
        userService.deleteUser(userId);
    }

    @Override
    @PostMapping("/consents")
    @ResponseStatus(HttpStatus.CREATED)
    public UserConsentResponse createConsent(@RequestBody Map<String, Object> request) {
        return UserConsentResponse.from(userService.createConsent(request));
    }

    @Override
    @PostMapping("/favorites")
    @ResponseStatus(HttpStatus.CREATED)
    public UserFavoriteResponse createFavorite(@RequestBody Map<String, Object> request) {
        return UserFavoriteResponse.from(userService.createFavorite(request));
    }

    @Override
    @DeleteMapping("/favorites/{favoriteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFavorite(@PathVariable Long favoriteId) {
        userService.deleteFavorite(favoriteId);
    }

    @Override
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public UserSessionResponse createSession(@RequestBody Map<String, Object> request) {
        return UserSessionResponse.from(userService.createSession(request));
    }

    @Override
    @PostMapping("/email-verifications")
    @ResponseStatus(HttpStatus.CREATED)
    public EmailVerificationResponse createEmailVerification(@RequestBody Map<String, Object> request) {
        return EmailVerificationResponse.from(userService.createEmailVerification(request));
    }
}
