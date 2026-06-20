package com.jinroon.jobe.global.client.dto.response;

public record ConsultationChatResponse(
        String content,
        String version,
        String requestId
) {
}
