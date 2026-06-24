package com.jinroon.jobe.global.client.dto.request;

import com.jinroon.jobe.domain.diagnosis.dto.DiagnosisProfileSnapshot;
import java.util.List;

public record DiagnosisProfileContext(
        String grade,
        String dreamJob,
        Double studyHours,
        String aspiration,
        List<String> selectedSubjects,
        String learningStyle,
        Integer exploreSpectrum
) {
    public DiagnosisProfileContext {
        selectedSubjects = selectedSubjects == null ? List.of() : List.copyOf(selectedSubjects);
    }

    public static DiagnosisProfileContext from(DiagnosisProfileSnapshot profile) {
        if (profile == null) {
            return null;
        }
        if (profile.grade() == null
                && profile.dreamJob() == null
                && profile.studyHours() == null
                && profile.aspiration() == null
                && profile.selectedSubjects().isEmpty()
                && profile.learningStyle() == null
                && profile.exploreSpectrum() == null) {
            return null;
        }
        return new DiagnosisProfileContext(
                profile.grade(),
                profile.dreamJob(),
                profile.studyHours(),
                profile.aspiration(),
                profile.selectedSubjects(),
                profile.learningStyle(),
                profile.exploreSpectrum()
        );
    }
}
