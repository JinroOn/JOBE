package com.jinroon.jobe.domain.diagnosis.dto.response;

import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisEssayAnswer;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisExamAnswer;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import java.util.List;

public record InProgressSessionResponse(
        DiagnosisSession session,
        List<DiagnosisExamAnswer> examAnswers,
        List<DiagnosisEssayAnswer> essayAnswers
) {
}
