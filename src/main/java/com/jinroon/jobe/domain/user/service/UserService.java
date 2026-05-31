package com.jinroon.jobe.domain.user.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.user.entity.EmailVerification;
import com.jinroon.jobe.domain.user.entity.Session;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.entity.UserConsent;
import com.jinroon.jobe.domain.user.entity.UserFavorite;
import com.jinroon.jobe.domain.user.repository.EmailVerificationRepository;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.user.repository.SessionRepository;
import com.jinroon.jobe.domain.user.repository.UserConsentRepository;
import com.jinroon.jobe.domain.user.repository.UserFavoriteRepository;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final UserFavoriteRepository userFavoriteRepository;
    private final SessionRepository sessionRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final MajorRepository majorRepository;

    public List<User> findUsers() {
        return userRepository.findAll();
    }

    public User getUser(Long userId) {
        return get(userRepository, userId, ErrorCode.USER_NOT_FOUND);
    }

    public List<UserConsent> findConsents(Long userId) {
        return userConsentRepository.findByUserId(userId);
    }

    public List<UserFavorite> findFavorites(Long userId) {
        return userFavoriteRepository.findByUserId(userId);
    }

    public List<Session> findSessions(Long userId) {
        return sessionRepository.findByUserId(userId);
    }

    public EmailVerification getEmailVerification(Long verificationId) {
        return get(emailVerificationRepository, verificationId, ErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND);
    }

    public EmailVerification getEmailVerificationForUser(Long verificationId, Long userId) {
        EmailVerification verification = getEmailVerification(verificationId);
        User user = getUser(userId);
        requireOwner(user.getEmail(), verification.getEmail());
        return verification;
    }

    @Transactional
    public User createUser(Map<String, Object> values) {
        return userRepository.save(EntityFormMapper.create(User.class, values));
    }

    @Transactional
    public User updateUser(Long userId, Map<String, Object> values) {
        User user = getUser(userId);
        EntityFormMapper.apply(user, values);
        return user;
    }

    @Transactional
    public void deleteUser(Long userId) {
        userRepository.delete(getUser(userId));
    }

    @Transactional
    public UserConsent createConsent(Map<String, Object> values) {
        return userConsentRepository.save(EntityFormMapper.create(UserConsent.class, values));
    }

    @Transactional
    public UserFavorite createFavorite(Map<String, Object> values) {
        Long userId = ((Number) values.get("userId")).longValue();
        Long majorId = ((Number) values.get("majorId")).longValue();
        get(userRepository, userId, ErrorCode.USER_NOT_FOUND);
        get(majorRepository, majorId, ErrorCode.MAJOR_NOT_FOUND);
        if (userFavoriteRepository.existsByUserIdAndMajorId(userId, majorId)) {
            throw new CustomException(ErrorCode.MAJOR_FAVORITE_DUPLICATE);
        }
        return userFavoriteRepository.save(EntityFormMapper.create(UserFavorite.class, values));
    }

    @Transactional
    public void deleteFavorite(Long favoriteId) {
        userFavoriteRepository.delete(get(userFavoriteRepository, favoriteId, ErrorCode.MAJOR_FAVORITE_NOT_FOUND));
    }

    @Transactional
    public void deleteFavoriteForUser(Long favoriteId, Long userId) {
        UserFavorite favorite = get(userFavoriteRepository, favoriteId, ErrorCode.MAJOR_FAVORITE_NOT_FOUND);
        requireOwner(favorite.getUserId(), userId);
        userFavoriteRepository.delete(favorite);
    }

    @Transactional
    public Session createSession(Map<String, Object> values) {
        return sessionRepository.save(EntityFormMapper.create(Session.class, values));
    }

    @Transactional
    public Session createSessionForUser(Map<String, Object> values, Long userId) {
        values.put("userId", userId);
        return createSession(values);
    }

    @Transactional
    public EmailVerification createEmailVerification(Map<String, Object> values) {
        return emailVerificationRepository.save(EntityFormMapper.create(EmailVerification.class, values));
    }

    private static void requireOwner(Long ownerId, Long userId) {
        if (!Objects.equals(ownerId, userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }

    private static void requireOwner(String ownerEmail, String email) {
        if (!Objects.equals(ownerEmail, email)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }
}
