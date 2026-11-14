package com.jinroon.jobe.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.user.entity.EmailVerification;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.entity.UserFavorite;
import com.jinroon.jobe.domain.user.repository.EmailVerificationRepository;
import com.jinroon.jobe.domain.user.repository.SessionRepository;
import com.jinroon.jobe.domain.user.repository.UserConsentRepository;
import com.jinroon.jobe.domain.user.repository.UserFavoriteRepository;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 3.1 이식 전 기준선 테스트: 즐겨찾기와 본인 소유 확인.
 * 본인 프로필 수정(PATCH /api/users/me)은 4.1에서 DTO를 분리하며 바뀌므로 여기서 고정하지 않는다.
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
        when(userFavoriteRepository.save(any(UserFavorite.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserFavorite favorite = service.createFavorite(favoriteValues(7L, 3L));

        assertThat(favorite.getUserId()).isEqualTo(7L);
        assertThat(favorite.getMajorId()).isEqualTo(3L);
    }

    @Test
    void createFavoriteRejectsDuplicate() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "a@example.com")));
        when(majorRepository.findById(3L)).thenReturn(Optional.of(newEntity(Major.class)));
        when(userFavoriteRepository.existsByUserIdAndMajorId(7L, 3L)).thenReturn(true);

        assertErrorCode(() -> service.createFavorite(favoriteValues(7L, 3L)), ErrorCode.MAJOR_FAVORITE_DUPLICATE);
        verify(userFavoriteRepository, never()).save(any());
    }

    @Test
    void createFavoriteRejectsUnknownMajor() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "a@example.com")));
        when(majorRepository.findById(3L)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.createFavorite(favoriteValues(7L, 3L)), ErrorCode.MAJOR_NOT_FOUND);
        verify(userFavoriteRepository, never()).save(any());
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

    private Map<String, Object> favoriteValues(Long userId, Long majorId) {
        Map<String, Object> values = new HashMap<>();
        values.put("userId", userId);
        values.put("majorId", majorId);
        return values;
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
