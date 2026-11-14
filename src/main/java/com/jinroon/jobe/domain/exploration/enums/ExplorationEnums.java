package com.jinroon.jobe.domain.exploration.enums;

public class ExplorationEnums {

    /** 회차 상태. 레거시 진단과 같은 값을 쓴다(11번 명세 3절). */
    public enum ExplorationSessionStatus {
        in_progress, completed, abandoned
    }

    /**
     * 실행 모드(D05). PUBLIC은 ACTIVE 버전 묶음만, DEV는 격리 환경의 REVIEWED 묶음,
     * PILOT은 별도 승인된 파일럿 대상에서만 시작한다. 클라이언트가 고를 수 없다.
     */
    public enum ExplorationRunMode {
        DEV, PILOT, PUBLIC
    }

    /** 결과 상태(D06). 후보 없음도 정상 완료다. */
    public enum ExplorationResultStatus {
        READY, LOW_EVIDENCE, NO_SUPPORTED_CANDIDATE
    }
}
