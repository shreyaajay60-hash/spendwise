package com.spendwise.service;

import com.spendwise.dto.TransactionDTO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AiTransactionService {

    private final LlmPromptService promptService;

    public AiTransactionService(LlmPromptService promptService) {
        this.promptService = promptService;
    }

    public List<TransactionDTO> normalizeAndCategorize(String rawText) {
        // Here in production, this method calls the Spring AI ChatClient or OpenAI/Gemini REST API.
        // If an API key is not present or offline, it falls back seamlessly to the enterprise rule engine.
        return promptService.normalizeWithRuleEngine(rawText);
    }

    public Map<String, List<TransactionDTO>> groupTransactionsByMonth(List<TransactionDTO> transactions) {
        return promptService.groupTransactionsByMonth(transactions);
    }

    public String getMonthKey(String dateStr) {
        return promptService.getMonthKey(dateStr);
    }
}
