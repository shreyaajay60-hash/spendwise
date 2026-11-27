package com.spendwise.controller;

import com.spendwise.dto.AnalysisResponseDTO;
import com.spendwise.dto.AnalyzeRequestDTO;
import com.spendwise.dto.TransactionDTO;
import com.spendwise.model.Transaction;
import com.spendwise.repository.TransactionRepository;
import com.spendwise.service.AiTransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/transactions")
@CrossOrigin(origins = "*") // Allows React frontend on localhost:5173
public class TransactionController {

    private final AiTransactionService aiTransactionService;
    private final TransactionRepository transactionRepository;

    public TransactionController(AiTransactionService aiTransactionService, TransactionRepository transactionRepository) {
        this.aiTransactionService = aiTransactionService;
        this.transactionRepository = transactionRepository;
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalysisResponseDTO> analyzeStatements(@RequestBody @Valid AnalyzeRequestDTO request) {
        List<TransactionDTO> parsed = aiTransactionService.normalizeAndCategorize(request.getRawText());
        return ResponseEntity.ok(new AnalysisResponseDTO("spring-boot-backend", parsed));
    }

    @GetMapping
    public ResponseEntity<List<Transaction>> getAll() {
        return ResponseEntity.ok(transactionRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<Transaction> create(@RequestBody TransactionDTO dto) {
        String id = dto.getId() != null ? dto.getId() : "tx-" + UUID.randomUUID().toString().substring(0, 8);
        String type = normalizeType(dto.getType());
        // Clean date: never substitute current date when missing
        String cleanDate = dto.getDate() != null ? dto.getDate().trim() : "";
        Transaction tx = new Transaction(
            id, cleanDate, dto.getRawDescription(), dto.getMerchantName(),
            dto.getCategory(), dto.getSubcategory(), dto.getAmount(), type,
            dto.getConfidenceScore(), dto.getIsRecurring(), dto.getTaxDeductible(),
            dto.getReasoning(), dto.getPaymentMethod(), dto.getStatus(), dto.getTags()
        );
        String key = fingerprint(tx);
        boolean duplicate = transactionRepository.findAll().stream().anyMatch(existing -> fingerprint(existing).equals(key));
        if (duplicate) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Transaction saved = transactionRepository.save(tx);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Transaction> update(@PathVariable String id, @RequestBody TransactionDTO dto) {
        Optional<Transaction> existingOpt = transactionRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Transaction existing = existingOpt.get();
        String updatedDate = dto.getDate() != null ? dto.getDate().trim() : existing.getDate();
        Transaction updated = new Transaction(
            id,
            updatedDate,
            dto.getRawDescription() != null ? dto.getRawDescription() : existing.getRawDescription(),
            dto.getMerchantName() != null ? dto.getMerchantName() : existing.getMerchantName(),
            dto.getCategory() != null ? dto.getCategory() : existing.getCategory(),
            dto.getSubcategory() != null ? dto.getSubcategory() : existing.getSubcategory(),
            dto.getAmount() != null ? dto.getAmount() : existing.getAmount(),
            dto.getType() != null ? normalizeType(dto.getType()) : existing.getType(),
            dto.getConfidenceScore() != null ? dto.getConfidenceScore() : existing.getConfidenceScore(),
            dto.getIsRecurring() != null ? dto.getIsRecurring() : existing.getIsRecurring(),
            dto.getTaxDeductible() != null ? dto.getTaxDeductible() : existing.getTaxDeductible(),
            dto.getReasoning() != null ? dto.getReasoning() : existing.getReasoning(),
            dto.getPaymentMethod() != null ? dto.getPaymentMethod() : existing.getPaymentMethod(),
            dto.getStatus() != null ? dto.getStatus() : existing.getStatus(),
            dto.getTags() != null ? dto.getTags() : existing.getTags()
        );
        Transaction saved = transactionRepository.save(updated);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (transactionRepository.existsById(id)) {
            transactionRepository.deleteById(id);
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clearAll() {
        transactionRepository.deleteAll();
        return ResponseEntity.noContent().build();
    }

    /**
     * Groups stored transactions using their actual month and year (YYYY-MM).
     */
    @GetMapping("/grouped-by-month")
    public ResponseEntity<Map<String, List<Transaction>>> getGroupedByMonth() {
        Map<String, List<Transaction>> grouped = new TreeMap<>(Collections.reverseOrder());
        for (Transaction tx : transactionRepository.findAll()) {
            String monthKey = getMonthKey(tx.getDate());
            grouped.computeIfAbsent(monthKey, k -> new ArrayList<>()).add(tx);
        }
        return ResponseEntity.ok(grouped);
    }

    /**
     * Computes financial summaries (expenses, income, net cash flow) grouped by actual month and year.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary(@RequestParam(required = false, defaultValue = "ALL") String month) {
        double expenses = 0;
        double income = 0;
        int count = 0;
        Map<String, Double> byCategory = new LinkedHashMap<>();

        for (Transaction tx : transactionRepository.findAll()) {
            String txMonth = getMonthKey(tx.getDate());
            if (!"ALL".equalsIgnoreCase(month) && !txMonth.equals(month)) {
                continue;
            }
            double amt = tx.getAmount() == null ? 0 : Math.abs(tx.getAmount());
            if (amt <= 0) continue;

            if ("CREDIT".equalsIgnoreCase(tx.getType())) {
                income += amt;
            } else {
                expenses += amt;
                String cat = tx.getCategory() != null ? tx.getCategory() : "General & Miscellaneous";
                byCategory.put(cat, byCategory.getOrDefault(cat, 0.0) + amt);
            }
            count++;
        }

        String topCat = "None";
        double topCatAmt = 0;
        for (Map.Entry<String, Double> entry : byCategory.entrySet()) {
            if (entry.getValue() > topCatAmt) {
                topCatAmt = entry.getValue();
                topCat = entry.getKey();
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("month", month);
        res.put("count", count);
        res.put("expenses", Math.round(expenses * 100.0) / 100.0);
        res.put("income", Math.round(income * 100.0) / 100.0);
        res.put("netCashFlow", Math.round((income - expenses) * 100.0) / 100.0);
        res.put("topCategory", topCat);
        res.put("topCategoryAmount", Math.round(topCatAmt * 100.0) / 100.0);
        return ResponseEntity.ok(res);
    }

    /**
     * Normalizes transaction direction:
     * DR = Debit = Money going out = Expense
     * CR = Credit = Money coming in = Income
     */
    public String normalizeType(String type) {
        if (type == null) return "DEBIT";
        String upper = type.trim().toUpperCase(Locale.ROOT);
        if (upper.matches(".*\\b(?:CREDIT|CR|INCOME|DEPOSIT|INFLOW)\\b.*") ||
            upper.equals("CR") || upper.equals("CREDIT") || upper.equals("UPI/CR") || upper.contains("CR")) {
            if (!upper.contains("DR") && !upper.contains("DEBIT")) {
                return "CREDIT";
            }
        }
        return "DEBIT";
    }

    public String getMonthKey(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return "unknown";
        String trimmed = dateStr.trim();
        Matcher m = Pattern.compile("^(\\d{4})-(\\d{2})(?:-\\d{2})?$").matcher(trimmed);
        if (m.find()) {
            int month = Integer.parseInt(m.group(2));
            if (month >= 1 && month <= 12) {
                return m.group(1) + "-" + m.group(2);
            }
        }
        return "unknown";
    }

    /** Same identity the ledger uses: date, direction, amount, and original description. */
    private String fingerprint(Transaction tx) {
        String type = normalizeType(tx.getType());
        double amount = tx.getAmount() == null ? 0 : Math.round(Math.abs(tx.getAmount()) * 100.0) / 100.0;
        String date = tx.getDate() == null ? "" : tx.getDate().trim();
        String desc = tx.getRawDescription() != null ? tx.getRawDescription() : "";
        if (desc.isBlank() && tx.getMerchantName() != null) desc = tx.getMerchantName();
        desc = desc.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return date + "|" + type + "|" + String.format(Locale.US, "%.2f", amount) + "|" + desc;
    }
}
