package com.spendwise.model;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_tx_date", columnList = "date"),
    @Index(name = "idx_tx_type", columnList = "type"),
    @Index(name = "idx_tx_category", columnList = "category")
})
public class Transaction {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "date", length = 32)
    private String date;

    @Column(name = "raw_description", columnDefinition = "TEXT")
    private String rawDescription;

    @Column(name = "merchant_name", length = 255)
    private String merchantName;

    @Column(name = "category", length = 128)
    private String category;

    @Column(name = "subcategory", length = 128)
    private String subcategory;

    @Column(name = "amount")
    private Double amount;

    @Column(name = "type", length = 32)
    private String type;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "is_recurring")
    private Boolean isRecurring;

    @Column(name = "tax_deductible")
    private Boolean taxDeductible;

    @Column(name = "reasoning", columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "payment_method", length = 128)
    private String paymentMethod;

    @Column(name = "status", length = 64)
    private String status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "transaction_tags", joinColumns = @JoinColumn(name = "transaction_id"))
    @Column(name = "tag", length = 64)
    private List<String> tags = new ArrayList<>();

    public Transaction() {}

    public Transaction(String id, String date, String rawDescription, String merchantName, 
                       String category, String subcategory, Double amount, String type, 
                       Double confidenceScore, Boolean isRecurring, Boolean taxDeductible, 
                       String reasoning, String paymentMethod, String status, List<String> tags) {
        this.id = id;
        this.date = date;
        this.rawDescription = rawDescription;
        this.merchantName = merchantName;
        this.category = category;
        this.subcategory = subcategory;
        this.amount = amount;
        this.type = type;
        this.confidenceScore = confidenceScore;
        this.isRecurring = isRecurring;
        this.taxDeductible = taxDeductible;
        this.reasoning = reasoning;
        this.paymentMethod = paymentMethod;
        this.status = status;
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public String getRawDescription() { return rawDescription; }
    public void setRawDescription(String rawDescription) { this.rawDescription = rawDescription; }

    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSubcategory() { return subcategory; }
    public void setSubcategory(String subcategory) { this.subcategory = subcategory; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public Boolean getIsRecurring() { return isRecurring; }
    public void setIsRecurring(Boolean recurring) { isRecurring = recurring; }

    public Boolean getTaxDeductible() { return taxDeductible; }
    public void setTaxDeductible(Boolean taxDeductible) { this.taxDeductible = taxDeductible; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
