package com.spendwise.service;

import com.spendwise.dto.TransactionDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmPromptServiceTest {

    private final LlmPromptService service = new LlmPromptService();

    @Test
    void emptyStatementProducesNoTransactions() {
        assertTrue(service.normalizeWithRuleEngine("").isEmpty());
        assertTrue(service.normalizeWithRuleEngine("   \n  ").isEmpty());
    }

    @Test
    void upiDebitIsExpenseAndUpiCreditIsIncome() {
        String text = """
            15/01/2025 UPI/DR/36328798482582/SWIGGY ₹450.00
            16/01/2025 UPI/CR/99887766554433/SALARY ₹50,000.00
            18/02/2025 UPI-SWIGGY-BANGALORE ₹220.00
            """;
        List<TransactionDTO> rows = service.normalizeWithRuleEngine(text);
        assertEquals(3, rows.size());

        TransactionDTO debit = rows.get(0);
        assertEquals("2025-01-15", debit.getDate());
        assertEquals("DEBIT", debit.getType());
        assertEquals(450.0, debit.getAmount(), 0.01);
        assertTrue(debit.getRawDescription().contains("UPI/DR"));

        TransactionDTO credit = rows.get(1);
        assertEquals("2025-01-16", credit.getDate());
        assertEquals("CREDIT", credit.getType());
        assertEquals(50000.0, credit.getAmount(), 0.01);

        TransactionDTO plainUpi = rows.get(2);
        assertEquals("2025-02-18", plainUpi.getDate());
        assertEquals("DEBIT", plainUpi.getType());
        assertEquals(220.0, plainUpi.getAmount(), 0.01);
    }

    @Test
    void upiCreditWithDoctorMerchantNameRemainsCredit() {
        String text = """
            15/06/2026 UPI/CR/482019/DR LAL PATHLABS REFUND ₹500.00
            20/07/2026 UPI/CR/998877/DR REDDYS LABS ₹1,200.00
            """;
        List<TransactionDTO> rows = service.normalizeWithRuleEngine(text);
        assertEquals(2, rows.size());
        assertEquals("CREDIT", rows.get(0).getType());
        assertEquals("2026-06-15", rows.get(0).getDate());
        assertEquals("CREDIT", rows.get(1).getType());
        assertEquals("2026-07-20", rows.get(1).getDate());
    }

    @Test
    void missingDateIsNotReplacedWithToday() {
        List<TransactionDTO> rows = service.normalizeWithRuleEngine("UPI/DR SWIGGY ₹125.00");
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).getDate() == null || rows.get(0).getDate().isBlank());
        assertEquals("DEBIT", rows.get(0).getType());
        assertTrue(rows.get(0).getTags().contains("Needs Date Review"));
    }

    @Test
    void csvUsesDebitAndCreditColumnsAndIgnoresBalance() {
        String csv = """
            Date,Narration,Debit,Credit,Balance
            01/06/2024,UPI/DR SWIGGY,450,,125000
            02/07/2024,UPI/CR SALARY,,80000,205000
            03/08/2024,AMAZON REFUND,,1200,206200
            04/09/2024,UPI/DR NETFLIX,649,,205551
            05/10/2024,UPI/DR UBER,340,,205211
            06/12/2023,UPI/DR FLIPKART,950,,100000
            """;
        List<TransactionDTO> rows = service.normalizeWithRuleEngine(csv);
        assertEquals(6, rows.size());

        assertEquals("2024-06-01", rows.get(0).getDate());
        assertEquals("DEBIT", rows.get(0).getType());
        assertEquals(450.0, rows.get(0).getAmount(), 0.01);

        assertEquals("2024-07-02", rows.get(1).getDate());
        assertEquals("CREDIT", rows.get(1).getType());
        assertEquals(80000.0, rows.get(1).getAmount(), 0.01);

        assertEquals("CREDIT", rows.get(2).getType());
        assertEquals(1200.0, rows.get(2).getAmount(), 0.01);
        assertNotEquals(206200.0, rows.get(2).getAmount());

        assertEquals("2023-12-06", rows.get(5).getDate());
        assertEquals("DEBIT", rows.get(5).getType());
    }

    @Test
    void statementDateAndPostingDateAreNotConfusedWithTransactionDate() {
        String csv = """
            Statement Date,Posting Date,Transaction Date,Description,Amount,Type
            26/11/2026,17/06/2026,15/06/2026,Swiggy,450,DEBIT
            26/11/2026,22/07/2026,20/07/2026,Amazon,1200,DEBIT
            26/11/2026,06/08/2026,05/08/2026,Uber,250,DEBIT
            26/11/2026,13/09/2026,12/09/2026,Salary,50000,CREDIT
            26/11/2026,19/10/2026,18/10/2026,Netflix,649,DEBIT
            """;
        List<TransactionDTO> rows = service.normalizeWithRuleEngine(csv);
        assertEquals(5, rows.size());

        // Each row must retain its genuine transaction date, NOT Statement Date (26/11/2026)
        assertEquals("2026-06-15", rows.get(0).getDate());
        assertEquals("2026-07-20", rows.get(1).getDate());
        assertEquals("2026-08-05", rows.get(2).getDate());
        assertEquals("2026-09-12", rows.get(3).getDate());
        assertEquals("CREDIT", rows.get(3).getType());
        assertEquals("2026-10-18", rows.get(4).getDate());

        // Verify monthly grouping
        Map<String, List<TransactionDTO>> grouped = service.groupTransactionsByMonth(rows);
        assertTrue(grouped.containsKey("2026-06"));
        assertTrue(grouped.containsKey("2026-07"));
        assertTrue(grouped.containsKey("2026-08"));
        assertTrue(grouped.containsKey("2026-09"));
        assertTrue(grouped.containsKey("2026-10"));
        assertFalse(grouped.containsKey("2026-11"), "Statement date of Nov 2026 must not overwrite transaction months");
    }

    @Test
    void duplicateStatementLinesAreReturnedOnce() {
        String text = """
            10/03/2025 UPI/DR/111/ZOMATO ₹380.00
            10/03/2025 UPI/DR/111/ZOMATO ₹380.00
            """;
        List<TransactionDTO> rows = service.normalizeWithRuleEngine(text);
        assertEquals(1, rows.size());
        assertEquals(380.0, rows.get(0).getAmount(), 0.01);
    }

    @Test
    void sixMonthsStayInTheirOwnYears() {
        String text = """
            15/01/2024 POS DEBIT SWIGGY ₹100.00
            15/03/2024 POS DEBIT UBER ₹200.00
            15/05/2024 POS DEBIT NETFLIX ₹300.00
            15/07/2024 POS DEBIT AMAZON ₹400.00
            15/09/2024 POS DEBIT FLIPKART ₹500.00
            15/11/2025 POS DEBIT ZOMATO ₹600.00
            """;
        List<TransactionDTO> rows = service.normalizeWithRuleEngine(text);
        assertEquals(6, rows.size());
        assertEquals("2024-01-15", rows.get(0).getDate());
        assertEquals("2024-03-15", rows.get(1).getDate());
        assertEquals("2024-05-15", rows.get(2).getDate());
        assertEquals("2024-07-15", rows.get(3).getDate());
        assertEquals("2024-09-15", rows.get(4).getDate());
        assertEquals("2025-11-15", rows.get(5).getDate());
        assertTrue(rows.stream().allMatch(r -> "DEBIT".equals(r.getType())));
    }
}
