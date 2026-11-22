package com.jinroon.jobe.domain.diagnosis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.diagnosis.entity.ExamQuestion;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.CompetencyCategory;
import com.jinroon.jobe.domain.diagnosis.repository.ExamQuestionRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ExamQuestionSeedInitializerTest {

    @Mock
    private ExamQuestionRepository examQuestionRepository;

    private ExamQuestionSeedInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new ExamQuestionSeedInitializer(
                examQuestionRepository,
                new ObjectMapper(),
                new DefaultResourceLoader()
        );
        ReflectionTestUtils.setField(
                initializer,
                "seedPath",
                "classpath:data/evaluation-questions-separated-60.json"
        );
        ReflectionTestUtils.setField(initializer, "defaultTimeLimitSec", 60);
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsEvaluationQuestionsWhenTableIsEmpty() {
        when(examQuestionRepository.count()).thenReturn(0L);

        initializer.run(null);

        ArgumentCaptor<Iterable<ExamQuestion>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(examQuestionRepository).saveAll(captor.capture());

        List<ExamQuestion> questions = new ArrayList<>();
        captor.getValue().forEach(questions::add);

        assertThat(questions).hasSize(60);
        ExamQuestion firstQuestion = questions.get(0);
        assertThat(firstQuestion.getCompetencyCategory()).isEqualTo(CompetencyCategory.math_logic);
        assertThat(firstQuestion.getQuestionText()).isNotBlank();
        assertThat(firstQuestion.getOptionA()).isNotBlank();
        assertThat(firstQuestion.getOptionB()).isNotBlank();
        assertThat(firstQuestion.getOptionC()).isNotBlank();
        assertThat(firstQuestion.getOptionD()).isNotBlank();
        assertThat(firstQuestion.getCorrectAnswer()).isEqualTo("C");
        assertThat(firstQuestion.getTimeLimitSec()).isEqualTo(60);
        assertThat(firstQuestion.getDifficulty()).isEqualTo(1);
        assertThat(firstQuestion.getWMathLogic()).isEqualTo(1.0f);
        assertThat(firstQuestion.getWProblemSolving()).isEqualTo(0.0f);
    }

    @Test
    void skipsImportWhenQuestionsAlreadyExist() {
        when(examQuestionRepository.count()).thenReturn(1L);

        initializer.run(null);

        verify(examQuestionRepository, never()).saveAll(any());
    }
}
