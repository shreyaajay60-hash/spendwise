package com.spendwise.dto;

import jakarta.validation.constraints.NotBlank;

public class AnalyzeRequestDTO {

    @NotBlank(message = "rawText statement cannot be empty")
    private String rawText;

    public AnalyzeRequestDTO() {}

    public AnalyzeRequestDTO(String rawText) {
        this.rawText = rawText;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }
}
