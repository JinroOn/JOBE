package com.jinroon.jobe.user.controller;

import com.jinroon.jobe.user.domain.EmailVerification;
import com.jinroon.jobe.user.domain.Session;
import com.jinroon.jobe.user.domain.User;
import com.jinroon.jobe.user.domain.UserConsent;
import com.jinroon.jobe.user.domain.UserFavorite;
import com.jinroon.jobe.user.service.UserService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    @GetMapping
    public List<User> findUsers() {
        return userService.findUsers();
    }

    @GetMapping("/{userId}")
    public User getUser(@PathVariable Long userId) {
        return userService.getUser(userId);
    }

    @GetMapping("/{userId}/consents")
    public List<UserConsent> findConsents(@PathVariable Long userId) {
        return userService.findConsents(userId);
    }

    @GetMapping("/{userId}/favorites")
    public List<UserFavorite> findFavorites(@PathVariable Long userId) {
        return userService.findFavorites(userId);
    }

    @GetMapping("/{userId}/sessions")
    public List<Session> findSessions(@PathVariable Long userId) {
        return userService.findSessions(userId);
    }

    @GetMapping("/email-verifications/{verificationId}")
    public EmailVerification getEmailVerification(@PathVariable Long verificationId) {
        return userService.getEmailVerification(verificationId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public User createUser(@RequestBody Map<String, Object> request) {
        return userService.createUser(request);
    }

    @PatchMapping("/{userId}")
    public User updateUser(@PathVariable Long userId, @RequestBody Map<String, Object> request) {
        return userService.updateUser(userId, request);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable Long userId) {
        userService.deleteUser(userId);
    }

    @PostMapping("/consents")
    @ResponseStatus(HttpStatus.CREATED)
    public UserConsent createConsent(@RequestBody Map<String, Object> request) {
        return userService.createConsent(request);
    }

    @PostMapping("/favorites")
    @ResponseStatus(HttpStatus.CREATED)
    public UserFavorite createFavorite(@RequestBody Map<String, Object> request) {
        return userService.createFavorite(request);
    }

    @DeleteMapping("/favorites/{favoriteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFavorite(@PathVariable Long favoriteId) {
        userService.deleteFavorite(favoriteId);
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Session createSession(@RequestBody Map<String, Object> request) {
        return userService.createSession(request);
    }

    @PostMapping("/email-verifications")
    @ResponseStatus(HttpStatus.CREATED)
    public EmailVerification createEmailVerification(@RequestBody Map<String, Object> request) {
        return userService.createEmailVerification(request);
    }
}
