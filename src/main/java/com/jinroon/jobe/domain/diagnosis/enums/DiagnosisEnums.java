package com.jinroon.jobe.domain.diagnosis.enums;

public class DiagnosisEnums {

    public enum DiagnosisStatus {
        in_progress, completed, abandoned
    }

    public enum CompetencyCategory {
        math_logic,
        problem_solving,
        info_tech,
        implementation,
        system_understanding,
        data_analysis,
        communication,
        collaboration,
        self_management
    }

    public enum QuestionType {
        objective,
        situation,
        preference,
        essay
    }
}
