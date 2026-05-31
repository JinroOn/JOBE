package com.jinroon.jobe.domain.major.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "전공 생성 및 수정 요청 데이터")
public class MajorRequest {

    @Schema(description = "전공명", example = "컴퓨터공학부")
    @Size(max = 100)
    private String name;

    @Schema(description = "카테고리", example = "공학계열")
    @Size(max = 50)
    private String category;

    @Schema(description = "난이도 (low, mid, high)", example = "high")
    private String difficulty;

    @Schema(description = "전공 설명", example = "소프트웨어와 하드웨어를 설계하고 구현하는 전공")
    private String description;

    @Schema(description = "진출 분야", example = "소프트웨어 개발자, 시스템 엔지니어")
    private String careerPaths;

    @Schema(description = "수리논리 역량 (0.0 ~ 100.0)", example = "85.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float reqMathLogic;

    @Schema(description = "문제해결 역량 (0.0 ~ 100.0)", example = "90.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float reqProblemSolving;
    
    @Schema(description = "정보통신 역량 (0.0 ~ 100.0)", example = "95.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float reqInfoTech;
    
    @Schema(description = "구현 역량 (0.0 ~ 100.0)", example = "90.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float reqImplementation;
    
    @Schema(description = "시스템이해 역량 (0.0 ~ 100.0)", example = "80.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float reqSystemUnderstanding;
    
    @Schema(description = "데이터분석 역량 (0.0 ~ 100.0)", example = "75.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float reqDataAnalysis;
    
    @Schema(description = "의사소통 역량 (0.0 ~ 100.0)", example = "70.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float reqCommunication;
    
    @Schema(description = "협업 역량 (0.0 ~ 100.0)", example = "80.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float reqCollaboration;
    
    @Schema(description = "자기관리 역량 (0.0 ~ 100.0)", example = "75.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float reqSelfManagement;

    @Schema(description = "논리탐구 성향 (0.0 ~ 100.0)", example = "80.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float tendLogicalInquiry;

    @Schema(description = "실용기술 성향 (0.0 ~ 100.0)", example = "70.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float tendPracticalTech;

    @Schema(description = "예술창의 성향 (0.0 ~ 100.0)", example = "20.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float tendArtCreative;

    @Schema(description = "사회협력 성향 (0.0 ~ 100.0)", example = "40.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float tendSocialCooperation;

    @Schema(description = "생활건강 성향 (0.0 ~ 100.0)", example = "30.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float tendLifeHealth;

    @Schema(description = "교육지도 성향 (0.0 ~ 100.0)", example = "35.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float tendEducationGuide;

    @Schema(description = "이론학술 성향 (0.0 ~ 100.0)", example = "75.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float tendTheoryAcademic;

    @Schema(description = "데이터분석 성향 (0.0 ~ 100.0)", example = "85.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float tendDataAnalytics;

    @Schema(description = "시스템운영 성향 (0.0 ~ 100.0)", example = "80.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float tendSystemOperation;

    @Schema(description = "수리논리 과락 기준 (0.0 ~ 100.0)", example = "50.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float thrMathLogic;

    @Schema(description = "정보통신 과락 기준 (0.0 ~ 100.0)", example = "50.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float thrInfoTech;
}
