package com.jinroon.jobe.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.user.dto.request.UserConsentRequest;
import com.jinroon.jobe.domain.user.dto.request.UserProfileUpdateRequest;
import com.jinroon.jobe.domain.user.dto.request.UserRequest;
import com.jinroon.jobe.domain.user.entity.EmailVerification;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.entity.UserConsent;
import com.jinroon.jobe.domain.user.entity.UserFavorite;
import com.jinroon.jobe.domain.user.repository.EmailVerificationRepository;
import com.jinroon.jobe.domain.user.repository.SessionRepository;
import com.jinroon.jobe.domain.user.repository.UserConsentRepository;
import com.jinroon.jobe.domain.user.repository.UserFavoriteRepository;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 회원 서비스 테스트: 즐겨찾기·본인 소유 확인(1단계 기준선),
 * 유일 제약 위반 처리(4단계), 본인 프로필 수정 필드 제한과 명시적 매핑(5단계·문서 4.1).
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserConsentRepository userConsentRepository;

    @Mock
    private UserFavoriteRepository userFavoriteRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private EmailVerificationRepository emailVerificationRepository;

    @Mock
    private MajorRepository majorRepository;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(
                userRepository,
                userConsentRepository,
                userFavoriteRepository,
                sessionRepository,
                emailVerificationRepository,
                majorRepository
        );
    }

    @Test
    void createFavoriteSavesUserAndMajor() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "a@example.com")));
        when(majorRepository.findById(3L)).thenReturn(Optional.of(newEntity(Major.class)));
        when(userFavoriteRepository.existsByUserIdAndMajorId(7L, 3L)).thenReturn(false);
        when(userFavoriteRepository.saveAndFlush(any(UserFavorite.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserFavorite favorite = service.createFavorite(7L, 3L);

        assertThat(favorite.getUserId()).isEqualTo(7L);
        assertThat(favorite.getMajorId()).isEqualTo(3L);
    }

    @Test
    void createFavoriteRejectsDuplicate() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "a@example.com")));
        when(majorRepository.findById(3L)).thenReturn(Optional.of(newEntity(Major.class)));
        when(userFavoriteRepository.existsByUserIdAndMajorId(7L, 3L)).thenReturn(true);

        assertErrorCode(() -> service.createFavorite(7L, 3L), ErrorCode.MAJOR_FAVORITE_DUPLICATE);
        verify(userFavoriteRepository, never()).saveAndFlush(any());
    }

    @Test
    void createFavoriteRejectsUnknownMajor() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "a@example.com")));
        when(majorRepository.findById(3L)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.createFavorite(7L, 3L), ErrorCode.MAJOR_NOT_FOUND);
        verify(userFavoriteRepository, never()).saveAndFlush(any());
    }

    @Test
    void createFavoriteConvertsConcurrentUniqueViolationToDuplicate() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "a@example.com")));
        when(majorRepository.findById(3L)).thenReturn(Optional.of(newEntity(Major.class)));
        when(userFavoriteRepository.existsByUserIdAndMajorId(7L, 3L)).thenReturn(false);
        when(userFavoriteRepository.saveAndFlush(any(UserFavorite.class)))
                .thenThrow(new DataIntegrityViolationException("uk_user_favorites_user_major"));

        assertErrorCode(() -> service.createFavorite(7L, 3L), ErrorCode.MAJOR_FAVORITE_DUPLICATE);
    }

    // ---- 4.1: 본인 프로필 수정은 닉네임·이미지만 ----

    @Test
    void updateProfileChangesOnlyNicknameAndImage() {
        User user = user(7L, "a@example.com");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        service.updateProfile(7L, new UserProfileUpdateRequest("새닉네임", "https://example.com/p.png"));

        assertThat(user.getNickname()).isEqualTo("새닉네임");
        assertThat(user.getProfileImageUrl()).isEqualTo("https://example.com/p.png");
        assertThat(user.getRoleName()).isEqualTo("member");
        assertThat(user.getStatusName()).isEqualTo("active");
        assertThat(user.getEmail()).isEqualTo("a@example.com");
    }

    @Test
    void updateProfileKeepsValuesWhenNull() {
        User user = user(7L, "a@example.com");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        service.updateProfile(7L, new UserProfileUpdateRequest(null, null));

        assertThat(user.getNickname()).isEqualTo("학생");
    }

    // ---- 관리자 생성·동의 ----

    @Test
    void createUserByAdminRejectsUnknownRole() {
        UserRequest request = adminUserRequest("new@example.com");
        request.setRole("superuser");
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

        assertErrorCode(() -> service.createUser(request), ErrorCode.INVALID_INPUT);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void createUserByAdminRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(true);

        assertErrorCode(() -> service.createUser(adminUserRequest("new@example.com")), ErrorCode.USER_EMAIL_DUPLICATE);
    }

    @Test
    void createUserByAdminConvertsConcurrentUniqueViolationToDuplicate() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertErrorCode(() -> service.createUser(adminUserRequest("new@example.com")), ErrorCode.USER_EMAIL_DUPLICATE);
    }

    @Test
    void createUserByAdminSetsRequestedRole() {
        UserRequest request = adminUserRequest("new@example.com");
        request.setRole("admin");
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User created = service.createUser(request);

        assertThat(created.getRoleName()).isEqualTo("admin");
        assertThat(created.getStatusName()).isEqualTo("active");
    }

    @Test
    void createConsentUsesServerTimeAndTreatsNullAsNotAgreed() {
        UserConsentRequest request = new UserConsentRequest();
        request.setTermsAgreed(true);
        request.setPrivacyAgreed(true);
        request.setAgreedAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        when(userConsentRepository.save(any(UserConsent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserConsent consent = service.createConsent(7L, request);

        assertThat(consent.getUserId()).isEqualTo(7L);
        assertThat(consent.getMarketingAgreed()).isFalse();
        assertThat(consent.getAgreedAt()).isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void deleteFavoriteForUserDeletesOwnFavorite() {
        UserFavorite favorite = favorite(10L, 7L, 3L);
        when(userFavoriteRepository.findById(10L)).thenReturn(Optional.of(favorite));

        service.deleteFavoriteForUser(10L, 7L);

        verify(userFavoriteRepository).delete(favorite);
    }

    @Test
    void deleteFavoriteForUserRejectsOtherUsersFavorite() {
        when(userFavoriteRepository.findById(10L)).thenReturn(Optional.of(favorite(10L, 8L, 3L)));

        assertErrorCode(() -> service.deleteFavoriteForUser(10L, 7L), ErrorCode.FORBIDDEN);
        verify(userFavoriteRepository, never()).delete(any());
    }

    @Test
    void deleteFavoriteForUserRejectsMissingFavorite() {
        when(userFavoriteRepository.findById(10L)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.deleteFavoriteForUser(10L, 7L), ErrorCode.MAJOR_FAVORITE_NOT_FOUND);
    }

    @Test
    void getEmailVerificationForUserRejectsOtherEmail() {
        EmailVerification verification = EmailVerification.issue("other@example.com", "123456",
                LocalDateTime.now().plusMinutes(10));
        when(emailVerificationRepository.findById(5L)).thenReturn(Optional.of(verification));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "a@example.com")));

        assertErrorCode(() -> service.getEmailVerificationForUser(5L, 7L), ErrorCode.FORBIDDEN);
    }

    @Test
    void getUserRejectsMissingUser() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.getUser(7L), ErrorCode.USER_NOT_FOUND);
    }

    private void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }

    private UserRequest adminUserRequest(String email) {
        UserRequest request = new UserRequest();
        request.setEmail(email);
        request.setNickname("관리자생성");
        return request;
    }

    private User user(Long id, String email) {
        User user = User.registerEmailUser(email, "pbkdf2$1$c2FsdA==$aGFzaA==", "학생", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private UserFavorite favorite(Long id, Long userId, Long majorId) {
        UserFavorite favorite = newEntity(UserFavorite.class);
        ReflectionTestUtils.setField(favorite, "id", id);
        ReflectionTestUtils.setField(favorite, "userId", userId);
        ReflectionTestUtils.setField(favorite, "majorId", majorId);
        return favorite;
    }

    private <T> T newEntity(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
