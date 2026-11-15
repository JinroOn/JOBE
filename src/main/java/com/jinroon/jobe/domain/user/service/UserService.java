package com.jinroon.jobe.domain.user.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.user.dto.request.EmailVerificationRequest;
import com.jinroon.jobe.domain.user.dto.request.UserConsentRequest;
import com.jinroon.jobe.domain.user.dto.request.UserProfileUpdateRequest;
import com.jinroon.jobe.domain.user.dto.request.UserRequest;
import com.jinroon.jobe.domain.user.entity.EmailVerification;
import com.jinroon.jobe.domain.user.entity.Session;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.entity.UserConsent;
import com.jinroon.jobe.domain.user.entity.UserFavorite;
import com.jinroon.jobe.domain.user.enums.UserEnums.LoginType;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserRole;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserStatus;
import com.jinroon.jobe.domain.user.repository.EmailVerificationRepository;
import com.jinroon.jobe.domain.user.repository.SessionRepository;
import com.jinroon.jobe.domain.user.repository.UserConsentRepository;
import com.jinroon.jobe.domain.user.repository.UserFavoriteRepository;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원·동의·즐겨찾기 서비스.
 * 요청 필드를 엔티티에 반사 적용하던 EntityFormMapper 대신, 허용 필드만 명시적으로 옮긴다.
 */
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
    private final UserWriteGuard writeGuard;
    private final AccountDeletionService accountDeletion;

    public List<User> findUsers() {
        return userRepository.findAll();
    }

    public User getUser(Long userId) {
        return get(userRepository, userId, ErrorCode.USER_NOT_FOUND);
    }

    public List<UserConsent> findConsents(Long userId) {
        return userConsentRepository.findByUserIdOrderByAgreedAtDescIdDesc(userId);
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

    /**
     * 관리자 전용(POST /api/users). 기존 요청 형식(UserRequest)은 유지하되 값은 명시적으로 옮긴다.
     */
    @Transactional
    public User createUser(UserRequest request) {
        if (request.getEmail() == null || request.getEmail().isBlank()
                || request.getNickname() == null || request.getNickname().isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }
        User user = User.createByAdmin(
                request.getEmail(),
                request.getPasswordHash(),
                request.getNickname(),
                request.getProfileImageUrl(),
                parseEnum(UserRole.class, request.getRole()),
                parseEnum(LoginType.class, request.getLoginType()),
                parseEnum(UserStatus.class, request.getStatus())
        );
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // existsByEmail 확인과 저장 사이에 같은 이메일이 먼저 저장된 경우(uk_users_email 위반)
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }
    }

    /**
     * 본인 프로필 수정(PATCH /api/users/me). 닉네임·프로필 이미지만 바꾼다.
     */
    @Transactional
    public User updateProfile(Long userId, UserProfileUpdateRequest request) {
        writeGuard.requireActive(userId);
        User user = getUser(userId);
        user.updateProfile(request.nickname(), request.profileImageUrl());
        return user;
    }

    @Transactional
    public void deleteUser(Long userId) {
        accountDeletion.delete(userId);
    }

    /**
     * 동의 기록은 서버가 현재 시각·약관 버전으로 남긴다. 요청의 agreedAt·termsVersion은 쓰지 않는다.
     */
    @Transactional
    public UserConsent createConsent(Long userId, UserConsentRequest request) {
        writeGuard.requireActive(userId);
        UserConsent previous = userConsentRepository.findFirstByUserIdOrderByAgreedAtDescIdDesc(userId).orElse(null);
        boolean terms = request.getTermsAgreed() != null ? request.getTermsAgreed()
                : previous != null && Boolean.TRUE.equals(previous.getTermsAgreed());
        boolean privacy = request.getPrivacyAgreed() != null ? request.getPrivacyAgreed()
                : previous != null && Boolean.TRUE.equals(previous.getPrivacyAgreed());
        boolean marketing = request.getMarketingAgreed() != null ? request.getMarketingAgreed()
                : previous != null && Boolean.TRUE.equals(previous.getMarketingAgreed());
        if (!terms || !privacy) throw new CustomException(ErrorCode.ACCOUNT_WITHDRAWAL_REQUIRED);
        return userConsentRepository.save(UserConsent.of(
                userId, terms, privacy, marketing
        ));
    }

    @Transactional
    public UserFavorite createFavorite(Long userId, Long majorId) {
        writeGuard.requireActive(userId);
        get(userRepository, userId, ErrorCode.USER_NOT_FOUND);
        majorRepository.findByIdForReference(majorId)
                .orElseThrow(() -> new CustomException(ErrorCode.MAJOR_NOT_FOUND));
        if (userFavoriteRepository.existsByUserIdAndMajorId(userId, majorId)) {
            throw new CustomException(ErrorCode.MAJOR_FAVORITE_DUPLICATE);
        }
        try {
            return userFavoriteRepository.saveAndFlush(UserFavorite.of(userId, majorId));
        } catch (DataIntegrityViolationException exception) {
            // 동시 요청으로 uk_user_favorites_user_major를 위반한 경우
            throw new CustomException(ErrorCode.MAJOR_FAVORITE_DUPLICATE);
        }
    }

    @Transactional
    public void deleteFavorite(Long favoriteId) {
        userFavoriteRepository.delete(get(userFavoriteRepository, favoriteId, ErrorCode.MAJOR_FAVORITE_NOT_FOUND));
    }

    @Transactional
    public void deleteFavoriteForUser(Long favoriteId, Long userId) {
        writeGuard.requireActive(userId);
        UserFavorite favorite = get(userFavoriteRepository, favoriteId, ErrorCode.MAJOR_FAVORITE_NOT_FOUND);
        if (!Objects.equals(favorite.getUserId(), userId)) throw new CustomException(ErrorCode.MAJOR_FAVORITE_NOT_FOUND);
        userFavoriteRepository.delete(favorite);
    }

    /**
     * 관리자 전용(POST /api/users/email-verifications). 기존 요청 형식은 유지하되 값은 명시적으로 옮긴다.
     */
    @Transactional
    public EmailVerification createEmailVerification(EmailVerificationRequest request) {
        if (request.getEmail() == null || request.getEmail().isBlank()
                || request.getToken() == null || request.getToken().isBlank()
                || request.getExpiresAt() == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        EmailVerification verification = EmailVerification.issue(
                request.getEmail(), request.getToken(), request.getExpiresAt());
        if (Boolean.TRUE.equals(request.getUsed())) {
            verification.markUsed();
        }
        return emailVerificationRepository.save(verification);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
    }

    private static void requireOwner(Long ownerId, Long userId) {
        if (!Objects.equals(ownerId, userId)) {
            throw new CustomException(ErrorCode.NOT_FOUND);
        }
    }

    private static void requireOwner(String ownerEmail, String email) {
        if (!Objects.equals(ownerEmail, email)) {
            throw new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND);
        }
    }
}
