package com.gossiplog.chat.dto;

import jakarta.validation.constraints.Size;

public record ReportSubmitRequest(
        @Size(max = 50, message = "reason은 50자를 초과할 수 없습니다") String reason,
        @Size(max = 500, message = "comment는 500자를 초과할 수 없습니다") String comment) {
}
