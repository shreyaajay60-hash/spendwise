package com.spendwise.dto;

import java.util.List;

public class AnalysisResponseDTO {
    private String source;
    private int count;
    private Double totalAmount;
    private List<TransactionDTO> transactions;

    public AnalysisResponseDTO() {}

    public AnalysisResponseDTO(String source, List<TransactionDTO> transactions) {
        this.source = source;
        this.transactions = transactions;
        this.count = transactions != null ? transactions.size() : 0;
        this.totalAmount = transactions != null ? transactions.stream()
                .mapToDouble(t -> t.getAmount() != null ? t.getAmount() : 0.0)
                .sum() : 0.0;
    }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public Double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Double totalAmount) { this.totalAmount = totalAmount; }

    public List<TransactionDTO> getTransactions() { return transactions; }
    public void setTransactions(List<TransactionDTO> transactions) { this.transactions = transactions; }
}
