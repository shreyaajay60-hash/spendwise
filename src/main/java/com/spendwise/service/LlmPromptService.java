package com.spendwise.service;

import com.spendwise.dto.TransactionDTO;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LlmPromptService {

    public static final String SYSTEM_PROMPT = """
        You are SpendWise Financial Intelligence Engine, an expert banking transaction normalizer and categorization AI.
        Your objective is to ingest raw, unformatted banking strings, POS codes, ACH withdrawal memos, and credit card statement lines,
        and convert each into a pristine, structured financial JSON object.

        Normalization Rules:
        1. Strip banking noise: Remove transaction prefixes like 'POS DEBIT', 'ACH WDL', 'DEBIT CRD W/D', 'CHECKCARD', 'SQ *', 'AMZN Mktp', reference IDs, phone numbers, state/zip abbreviations.
        2. Extract the true Merchant Name in clear human-readable title casing.
        3. Categorize into standard taxonomy: Food & Dining, Transportation, Shopping & Retail, Bills & Utilities, Entertainment & Media, Software & Cloud, Health & Wellness, Travel & Lodging, General & Miscellaneous.
        4. Extract the exact transaction date in YYYY-MM-DD format; never substitute today's date when missing.
        5. Infer recurring subscription status.
        6. Detect tax-deductible expense eligibility.
        7. Calculate confidence score between 0.85 and 0.99 based on entity clarity.
        """;

    private static final Map<String, Integer> MONTH_MAP = Map.ofEntries(
        Map.entry("jan", 1), Map.entry("january", 1),
        Map.entry("feb", 2), Map.entry("february", 2),
        Map.entry("mar", 3), Map.entry("march", 3),
        Map.entry("apr", 4), Map.entry("april", 4),
        Map.entry("may", 5),
        Map.entry("jun", 6), Map.entry("june", 6),
        Map.entry("jul", 7), Map.entry("july", 7),
        Map.entry("aug", 8), Map.entry("august", 8),
        Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("september", 9),
        Map.entry("oct", 10), Map.entry("october", 10),
        Map.entry("nov", 11), Map.entry("november", 11),
        Map.entry("dec", 12), Map.entry("december", 12)
    );

    private static final Pattern MMM_DD_YYYY = Pattern.compile(
        "\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)[,\\s]+(\\d{1,2})(?:st|nd|rd|th)?[,\\s]+(\\d{2,4})\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DD_MMM_YYYY = Pattern.compile(
        "\\b(\\d{1,2})(?:st|nd|rd|th)?[\\s/\\-.]+(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)[\\s/\\-.,]+(\\d{2,4})\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern YYYY_MM_DD = Pattern.compile("\\b(\\d{4})[/\\-.](\\d{1,2})[/\\-.](\\d{1,2})\\b");
    private static final Pattern NUMERIC_DATE = Pattern.compile("\\b(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})\\b");
    private static final Pattern SHORT_MM_DD = Pattern.compile("\\b(?:POS\\s+DEBIT|DEBIT|WDL|POS|DATE|TXN)\\s+(\\d{1,2})/(\\d{1,2})(?![/\\d])", Pattern.CASE_INSENSITIVE);

    private static final Pattern TXN_LABELED_DATE = Pattern.compile(
        "(?i)(?:transaction\\s*date|txn\\.?\\s*date|trans\\.?\\s*date|tran\\.?\\s*date|trxn\\.?\\s*date|tx\\.?\\s*date|date\\s*of\\s*transaction|purchase\\s*date|order\\s*date|booking\\s*date|activity\\s*date)\\s*[:\\-]?\\s*(\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}|\\d{1,2}[\\s/\\-.]+[a-zA-Z]{3,9}[\\s/\\-.]+\\d{2,4}|[a-zA-Z]{3,9}[,\\s]+\\d{1,2}(?:st|nd|rd|th)?[,\\s]+\\d{2,4})"
    );

    private static final Pattern EXCLUDED_LABELED_DATE = Pattern.compile(
        "(?i)(?:statement\\s*date|stmt\\.?\\s*date|statement\\s*period|payment\\s*due\\s*date|due\\s*date|bill\\s*date|billing\\s*date|closing\\s*date|opening\\s*date|cycle\\s*date|invoice\\s*date|generated\\s*(?:on|date)|printed\\s*(?:on|date)|as\\s*of\\s*date|report\\s*date)\\s*[:\\-]?\\s*(?:\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}|\\d{1,2}[\\s/\\-.]+[a-zA-Z]{3,9}[\\s/\\-.]+\\d{2,4}|[a-zA-Z]{3,9}[,\\s]+\\d{1,2}(?:st|nd|rd|th)?[,\\s]+\\d{2,4})"
    );

    private static final Pattern POST_LABELED_DATE = Pattern.compile(
        "(?i)(?:post(?:ing|ed)?\\s*date|settled\\s*date|value\\s*date)\\s*[:\\-]?\\s*(?:\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}|\\d{1,2}[\\s/\\-.]+[a-zA-Z]{3,9}[\\s/\\-.]+\\d{2,4}|[a-zA-Z]{3,9}[,\\s]+\\d{1,2}(?:st|nd|rd|th)?[,\\s]+\\d{2,4})"
    );

    private static final List<MerchantRule> KNOWN_MERCHANTS = List.of(
        new MerchantRule("(?i)swiggy", "Swiggy", "Food & Dining", "Delivery", false, false, "Recognized food delivery platform."),
        new MerchantRule("(?i)zomato", "Zomato", "Food & Dining", "Delivery", false, false, "Recognized restaurant food delivery network."),
        new MerchantRule("(?i)flipkart", "Flipkart", "Shopping & Retail", "Online Shopping", false, false, "Recognized e-commerce marketplace order."),
        new MerchantRule("(?i)(jio|airtel|vodafone|bsnl)", "Telecom / Fiber Recharge", "Bills & Utilities", "Mobile Phone", true, false, "Recognized telecom or broadband recharge."),
        new MerchantRule("(?i)(uber(\\s*\\*trip|\\s*eats|\\s*rides|\\s*india)?|ola|lyft)", "Uber", "Transportation", "Rideshare", false, false, "Recognized mobility & rideshare network."),
        new MerchantRule("(?i)netflix(\\.com)?", "Netflix", "Entertainment & Media", "Streaming Services", true, false, "Digital video streaming monthly subscription."),
        new MerchantRule("(?i)spotify", "Spotify", "Entertainment & Media", "Streaming Services", true, false, "Digital audio streaming subscription."),
        new MerchantRule("(?i)(amazon|amzn\\s*mktp|amzn\\.com)", "Amazon", "Shopping & Retail", "Online Shopping", false, false, "E-commerce retail marketplace purchase."),
        new MerchantRule("(?i)blue\\s*bottle", "Blue Bottle Coffee", "Food & Dining", "Coffee Shops", false, false, "Specialty cafe chain POS charge."),
        new MerchantRule("(?i)starbucks", "Starbucks Coffee", "Food & Dining", "Coffee Shops", false, false, "Retail coffee chain transaction."),
        new MerchantRule("(?i)trader\\s*joe'?s", "Trader Joe's", "Food & Dining", "Groceries", false, false, "Supermarket grocery store chain purchase."),
        new MerchantRule("(?i)whole\\s*f(oo)?ds", "Whole Foods Market", "Food & Dining", "Groceries", false, false, "Organic grocery retail transaction."),
        new MerchantRule("(?i)(pg&e|pacific\\s*gas|pge)", "Pacific Gas & Electric (PG&E)", "Bills & Utilities", "Electricity", true, false, "Electric & gas municipal utility bill."),
        new MerchantRule("(?i)(comcast|xfinity)", "Comcast / Xfinity", "Bills & Utilities", "Internet", true, false, "Broadband telecommunications provider."),
        new MerchantRule("(?i)chevron", "Chevron", "Transportation", "Gas & Fuel", false, false, "Retail fuel station purchase."),
        new MerchantRule("(?i)equinox", "Equinox Fitness", "Health & Wellness", "Gym Membership", true, false, "Fitness club monthly membership dues."),
        new MerchantRule("(?i)(github|copilot)", "GitHub Copilot", "Software & Cloud", "Developer Tools", true, true, "Software developer AI tools subscription."),
        new MerchantRule("(?i)(aws|amazon\\s*web\\s*services)", "Amazon Web Services (AWS)", "Software & Cloud", "Cloud Hosting", true, true, "Cloud infrastructure compute & storage invoice."),
        new MerchantRule("(?i)sweetgreen", "Sweetgreen", "Food & Dining", "Fast Food", false, false, "Fast-casual healthy dining chain."),
        new MerchantRule("(?i)united\\s*air(lines)?", "United Airlines", "Travel & Lodging", "Flights", false, false, "Commercial passenger flight ticket."),
        new MerchantRule("(?i)(chatgpt|openai)", "OpenAI ChatGPT Plus", "Software & Cloud", "SaaS Subscriptions", true, true, "Generative AI platform monthly subscription."),
        new MerchantRule("(?i)(salary|payroll)", "Salary / Payroll", "General & Miscellaneous", "Income", false, false, "Recognized monthly payroll or salary credit."),
        new MerchantRule("(?i)(blinkit|zepto|instamart|bigbasket)", "Quick Commerce / Groceries", "Food & Dining", "Groceries", false, false, "Recognized instant grocery & daily essentials delivery service."),
        new MerchantRule("(?i)(reliance\\s*smart|dmart|more\\s*retail)", "Supermarket / Retail", "Food & Dining", "Groceries", false, false, "Identified supermarket and retail grocery chain purchase.")
    );

    public String buildPrompt(String rawStatementBatch) {
        return SYSTEM_PROMPT + "\n\nRaw statement batch:\n" + rawStatementBatch;
    }

    public List<TransactionDTO> normalizeWithRuleEngine(String rawText) {
        List<TransactionDTO> list = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) return list;

        List<String> rawLines = Arrays.stream(rawText.split("\\r?\\n"))
            .map(String::trim)
            .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith("//") && !l.matches("^[\\|\\s\\-:\\=_]+$"))
            .toList();

        if (rawLines.isEmpty()) return list;

        boolean hasTabularHeader = rawLines.stream().limit(15).anyMatch(l ->
            l.matches("(?i).*\\b(date|txn|trans|tran|description|narration|particulars|merchant|amount|debit|credit|withdrawal|deposit)\\b.*") &&
            l.matches(".*[,;\\t\\|].*")
        );

        if (hasTabularHeader) {
            return dedupe(parseTabularLines(rawLines));
        }

        boolean preferMdy = detectMdyOrder(rawLines, "");
        for (String line : rawLines) {
            if (isMetadataLine(line)) continue;
            TransactionDTO dto = parseLine(line, null, null, null, preferMdy, null, line);
            if (dto != null && dto.getAmount() != null && dto.getAmount() > 0) list.add(dto);
        }
        return dedupe(list);
    }

    private List<TransactionDTO> parseTabularLines(List<String> rawLines) {
        List<TransactionDTO> results = new ArrayList<>();
        String joinedSample = String.join("\n", rawLines.subList(0, Math.min(rawLines.size(), 10)));
        boolean isPipe = joinedSample.contains("|") && !joinedSample.contains(",");

        int headerIdx = -1;
        List<String> headerCells = List.of();
        for (int i = 0; i < Math.min(rawLines.size(), 15); i++) {
            String line = rawLines.get(i);
            if (isMetadataLine(line)) continue;
            List<String> cells = splitCells(line, isPipe).stream().map(String::toLowerCase).toList();
            if (cells.size() < 2) continue;
            long hits = cells.stream().filter(c ->
                c.matches("(?i).*(transaction\\s*date|txn\\.?\\s*date|trans\\.?\\s*date|tran\\.?\\s*date|date|merchant|payee|description|narration|particulars|amount|debit|credit|withdrawal|deposit).*")
            ).count();
            if (hits >= 2) {
                headerIdx = i;
                headerCells = cells;
                break;
            }
        }

        int startRow = headerIdx != -1 ? headerIdx + 1 : 0;
        int dateCol = -1, descCol = -1, debitCol = -1, creditCol = -1, amountCol = -1, typeCol = -1, balanceCol = -1, catCol = -1;

        if (headerIdx != -1) {
            dateCol = findDateColumnIndex(headerCells);

            for (int i = 0; i < headerCells.size(); i++) {
                String c = headerCells.get(i);
                if (balanceCol == -1 && c.matches("(?i).*(balance|closing\\s*bal|running\\s*bal|avail(?:able)?\\s*bal|\\bbal\\b).*")) {
                    balanceCol = i;
                }
            }

            for (int i = 0; i < headerCells.size(); i++) {
                String c = headerCells.get(i);
                boolean balanceOrRef = i == balanceCol || c.matches("(?i).*(balance|\\bbal\\b|\\bref\\b|utr|chq|cheque|serial).*");
                boolean isTypeCol = c.matches("(?i)^(?:dr/cr|cr/dr|txn\\s*type|transaction\\s*type|direction|indicator|type)$");

                if (typeCol == -1 && isTypeCol) typeCol = i;
                if (descCol == -1 && c.matches("(?i).*(merchant|payee|particulars|narration|narrative|description|details|memo|remarks|title).*")) descCol = i;
                if (debitCol == -1 && !balanceOrRef && !isTypeCol && c.matches("(?i).*\\b(?:debit|withdrawal|withdrawals|dr|spent|expense|paid\\s*out|outflow)\\b.*")) debitCol = i;
                if (creditCol == -1 && !balanceOrRef && !isTypeCol && c.matches("(?i).*\\b(?:credit|deposit|deposits|cr|received|inflow)\\b.*")) creditCol = i;
                if (catCol == -1 && c.matches("(?i).*\\bcategory\\b.*")) catCol = i;
            }

            for (int i = 0; i < headerCells.size(); i++) {
                String c = headerCells.get(i);
                boolean balanceOrRef = i == balanceCol || c.matches("(?i).*(balance|\\bbal\\b|\\bref\\b|utr|chq|cheque|serial).*");
                boolean isTypeCol = i == typeCol;
                if (amountCol == -1 && !balanceOrRef && !isTypeCol && i != debitCol && i != creditCol && c.matches("(?i).*\\b(?:amount|amt|value|price|total)\\b.*")) {
                    amountCol = i;
                }
            }
        }

        String headerHint = (headerIdx != -1 && dateCol != -1) ? headerCells.get(dateCol) : "";
        List<String> dataInspect = new ArrayList<>();
        for (int i = startRow; i < rawLines.size(); i++) {
            if (dateCol != -1) {
                List<String> cells = splitCells(rawLines.get(i), isPipe);
                if (dateCol < cells.size()) dataInspect.add(cells.get(dateCol));
            } else {
                dataInspect.add(rawLines.get(i));
            }
        }
        boolean preferMdy = detectMdyOrder(dataInspect, headerHint);

        for (int i = startRow; i < rawLines.size(); i++) {
            String line = rawLines.get(i);
            if (isMetadataLine(line)) continue;
            List<String> cells = splitCells(line, isPipe);
            if (cells.isEmpty() || cells.stream().allMatch(String::isBlank)) continue;

            String extractedDate = null;
            if (dateCol != -1 && dateCol < cells.size()) {
                DateMatch dm = parseDate(cells.get(dateCol), preferMdy);
                if (dm != null) extractedDate = dm.formatted;
            } else if (headerIdx == -1) {
                DateMatch dm = parseDate(line, preferMdy);
                if (dm != null) extractedDate = dm.formatted;
            }

            String desc = (descCol != -1 && descCol < cells.size()) ? cells.get(descCol) : line;
            String explicitType = null;
            if (typeCol != -1 && typeCol < cells.size() && cells.get(typeCol) != null) {
                String typeCell = cells.get(typeCol).trim().toUpperCase(Locale.ROOT);
                if (typeCell.matches(".*\\b(?:CR|CREDIT|DEPOSIT|INCOME|INFLOW)\\b.*") || typeCell.equals("CR") || typeCell.contains("CR")) {
                    if (!typeCell.contains("DR") && !typeCell.contains("DEBIT")) {
                        explicitType = "CREDIT";
                    }
                } else if (typeCell.matches(".*\\b(?:DR|DEBIT|WITHDRAWAL|EXPENSE|OUTFLOW)\\b.*") || typeCell.equals("DR") || typeCell.contains("DR")) {
                    explicitType = "DEBIT";
                }
            }

            AmountInfo debitInfo = (debitCol != -1 && debitCol < cells.size()) ? parseAmountCell(cells.get(debitCol)) : null;
            AmountInfo creditInfo = (creditCol != -1 && creditCol < cells.size()) ? parseAmountCell(cells.get(creditCol)) : null;
            AmountInfo singleInfo = (amountCol != -1 && amountCol < cells.size()) ? parseAmountCell(cells.get(amountCol)) : null;

            Double amt = null;
            boolean debitPresent = debitInfo != null && debitInfo.value > 0;
            boolean creditPresent = creditInfo != null && creditInfo.value > 0;

            // Separate Debit & Credit columns take strict priority over single Amount column
            if (debitPresent && !creditPresent) {
                amt = debitInfo.value;
                explicitType = "DEBIT";
            } else if (creditPresent && !debitPresent) {
                amt = creditInfo.value;
                explicitType = "CREDIT";
            } else if (singleInfo != null && singleInfo.value > 0) {
                amt = singleInfo.value;
                if (explicitType == null && singleInfo.hint != null) explicitType = singleInfo.hint;
            } else if (debitPresent) {
                amt = debitInfo.value;
                explicitType = "DEBIT";
            } else if (creditPresent) {
                amt = creditInfo.value;
                explicitType = "CREDIT";
            }

            String explicitCat = null;
            if (catCol != -1 && catCol < cells.size() && cells.get(catCol) != null && !cells.get(catCol).isBlank()) {
                String catCell = cells.get(catCol).trim();
                if (!catCell.matches("(?i)^(?:DR|CR|DEBIT|CREDIT)$")) explicitCat = catCell;
            }

            TransactionDTO dto = parseLine(desc, extractedDate, amt, explicitCat, preferMdy, explicitType, line);
            if (dto != null && dto.getAmount() != null && dto.getAmount() > 0) {
                dto.setRawDescription(line);
                results.add(dto);
            }
        }
        return results;
    }

    /**
     * Accurately identifies the Transaction Date column while preventing confusion
     * with Statement Date, Due Date, or Posting Date.
     */
    public int findDateColumnIndex(List<String> headers) {
        if (headers == null || headers.isEmpty()) return -1;

        Pattern excluded = Pattern.compile(
            "(?i).*(statement\\s*date|stmt\\.?\\s*date|due\\s*date|payment\\s*due|bill\\s*date|billing\\s*date|" +
            "cycle\\s*date|invoice\\s*date|closing\\s*date|opening\\s*date|generated|printed|export|birth|dob|" +
            "as\\s*of\\s*date|report\\s*date|run\\s*date).*"
        );

        // Tier 1: Explicit transaction date column (highest priority)
        Pattern txnDatePattern = Pattern.compile(
            "(?i).*(transaction\\s*date|txn\\.?\\s*date|trans\\.?\\s*date|tran\\.?\\s*date|trxn\\.?\\s*date|" +
            "tx\\.?\\s*date|date\\s*of\\s*transaction|purchase\\s*date|order\\s*date|activity\\s*date|" +
            "booking\\s*date|trade\\s*date|operation\\s*date).*"
        );
        for (int i = 0; i < headers.size(); i++) {
            String c = headers.get(i).trim();
            if (!excluded.matcher(c).matches() && txnDatePattern.matcher(c).matches()) {
                return i;
            }
        }

        // Tier 2: Pure Date column e.g. "Date", "Date (DD/MM/YYYY)", "Date (MM/DD/YYYY)", "Txn Date"
        for (int i = 0; i < headers.size(); i++) {
            String c = headers.get(i).trim();
            if (!excluded.matcher(c).matches() && c.matches("(?i)^date(?:\\s*\\(.*\\))?$")) {
                return i;
            }
        }

        // Tier 3: Value date (if no explicit transaction date)
        for (int i = 0; i < headers.size(); i++) {
            String c = headers.get(i).trim();
            if (!excluded.matcher(c).matches() && c.matches("(?i).*val(?:ue)?\\s*date.*")) {
                return i;
            }
        }

        // Tier 4: Posting date / Settled date (only if no transaction date column is present)
        for (int i = 0; i < headers.size(); i++) {
            String c = headers.get(i).trim();
            if (!excluded.matcher(c).matches() && c.matches("(?i).*post(?:ing|ed)?\\s*date|settled\\s*date.*")) {
                return i;
            }
        }

        // Tier 5: Any other column containing "date" that is NOT excluded
        for (int i = 0; i < headers.size(); i++) {
            String c = headers.get(i).trim();
            if (!excluded.matcher(c).matches() && c.matches("(?i).*\\bdate\\b.*")) {
                return i;
            }
        }

        return -1;
    }

    private List<String> splitCells(String line, boolean isPipe) {
        String target = isPipe ? line.replaceAll("^\\|+|\\|+$", "") : line;
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < target.length(); i++) {
            char ch = target.charAt(i);
            if (ch == '"' || ch == '\'') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && (isPipe ? ch == '|' : (ch == ',' || ch == '\t' || ch == ';'))) {
                cells.add(cur.toString().trim().replaceAll("^[\"']|[\"']$", ""));
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString().trim().replaceAll("^[\"']|[\"']$", ""));
        return cells;
    }

    /**
     * Determines whether a line contains statement header metadata rather than a transaction.
     */
    public boolean isMetadataLine(String line) {
        if (line == null || line.isBlank()) return true;
        String trimmed = line.trim();
        String clean = trimmed.replaceAll("^[\\|\\s\\-\\:\\=\\_\\*]+|[\\|\\s\\-\\:\\=\\_\\*]+$", "").trim();
        if (clean.isEmpty() || clean.matches("^[\\|\\s\\-\\:\\=\\_\\*]+$")) return true;

        String lower = clean.toLowerCase(Locale.ROOT);
        boolean hasTxnKeywords = lower.matches("(?i).*(transaction\\s*date|txn\\.?\\s*date|trans\\.?\\s*date|tran\\.?\\s*date|swiggy|zomato|amazon|flipkart|uber|netflix|spotify|pos\\s+debit|ach\\s+wdl|upi/|upi-).*");
        if (hasTxnKeywords) return false;

        return lower.matches("(?i)^(statement\\s*date|stmt\\.?\\s*date|statement\\s*period|statement\\s*summary|payment\\s*due\\s*date|due\\s*date|bill\\s*date|billing\\s*date|invoice\\s*date|opening\\s*balance|closing\\s*balance|available\\s*credit|available\\s*balance|credit\\s*limit|total\\s*amount\\s*due|minimum\\s*amount\\s*due|account\\s*(?:no|number|name|holder|summary)|customer\\s*(?:id|name)|branch|ifsc|currency|page\\s*\\d+|generated\\s*(?:on|by)|printed\\s*(?:on|by)|bank\\s*statement|transaction\\s*history|statement\\s*of\\s*account).*");
    }

    private boolean detectMdyOrder(List<String> lines, String hintText) {
        if (hintText != null && hintText.matches("(?i).*\\bmm[/\\-.]dd(?:[/\\-.]yy(?:yy)?)?\\b.*")) return true;
        if (hintText != null && hintText.matches("(?i).*\\bdd[/\\-.]mm(?:[/\\-.]yy(?:yy)?)?\\b.*")) return false;

        int dmyEvidence = 0, mdyEvidence = 0;
        for (String line : lines) {
            if (line == null) continue;
            if (line.matches("(?i).*\\bmm[/\\-.]dd(?:[/\\-.]yy(?:yy)?)?\\b.*")) return true;
            Matcher m = NUMERIC_DATE.matcher(line);
            while (m.find()) {
                int p1 = Integer.parseInt(m.group(1));
                int p2 = Integer.parseInt(m.group(2));
                if (p1 > 12 && p2 >= 1 && p2 <= 12) dmyEvidence++;
                else if (p2 > 12 && p1 >= 1 && p1 <= 12) mdyEvidence++;
            }
        }
        return mdyEvidence > 0 && dmyEvidence == 0;
    }

    /**
     * Extracts date cleanly:
     * - Respects transaction date label when present
     * - Strips statement date, payment due date, etc.
     * - Never substitutes today's date when missing
     */
    public DateMatch parseDate(String raw, boolean preferMdy) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();

        // 1. If line is exclusively metadata date line, skip it
        if (trimmed.matches("(?i)^(?:statement\\s*date|stmt\\.?\\s*date|payment\\s*due\\s*date|due\\s*date|bill\\s*date|billing\\s*date).*")
            && !trimmed.matches("(?i).*(?:transaction\\s*date|txn\\.?\\s*date|trans\\.?\\s*date|tran\\.?\\s*date).*")) {
            return null;
        }

        // 2. Check for explicit transaction date label
        Matcher txnM = TXN_LABELED_DATE.matcher(trimmed);
        if (txnM.find()) {
            String extracted = txnM.group(1).trim();
            DateMatch dm = parseDateExact(extracted, preferMdy);
            if (dm != null) return dm;
        }

        // 3. Remove statement date / due date tokens before general extraction
        String segment = EXCLUDED_LABELED_DATE.matcher(trimmed).replaceAll(" ").trim();
        if (segment.isEmpty()) return null;

        // 4. If posting date label is present alongside transaction date, strip posting date
        if (POST_LABELED_DATE.matcher(segment).find() && NUMERIC_DATE.matcher(segment).results().count() > 1) {
            String strippedPost = POST_LABELED_DATE.matcher(segment).replaceAll(" ").trim();
            if (!strippedPost.isEmpty()) segment = strippedPost;
        }

        return parseDateExact(segment, preferMdy);
    }

    private DateMatch parseDateExact(String segment, boolean preferMdy) {
        // 1. MMM DD, YYYY or MMMM DD, YYYY (e.g. Jun 15, 2026 / June 15, 2026)
        Matcher m1 = MMM_DD_YYYY.matcher(segment);
        if (m1.find()) {
            int month = MONTH_MAP.getOrDefault(m1.group(1).toLowerCase(Locale.ROOT), 0);
            int day = Integer.parseInt(m1.group(2));
            int year = normalizeYear(Integer.parseInt(m1.group(3)));
            if (isValidDate(year, month, day)) {
                return new DateMatch(formatIso(year, month, day), m1.group(0));
            }
        }

        // 2. DD MMM YYYY or DD MMMM YYYY (e.g. 15 Jun 2026 / 15-June-2026)
        Matcher m2 = DD_MMM_YYYY.matcher(segment);
        if (m2.find()) {
            int day = Integer.parseInt(m2.group(1));
            int month = MONTH_MAP.getOrDefault(m2.group(2).toLowerCase(Locale.ROOT), 0);
            int year = normalizeYear(Integer.parseInt(m2.group(3)));
            if (isValidDate(year, month, day)) {
                return new DateMatch(formatIso(year, month, day), m2.group(0));
            }
        }

        // 3. YYYY-MM-DD or YYYY/MM/DD
        Matcher m3 = YYYY_MM_DD.matcher(segment);
        if (m3.find()) {
            int year = Integer.parseInt(m3.group(1));
            int month = Integer.parseInt(m3.group(2));
            int day = Integer.parseInt(m3.group(3));
            if (isValidDate(year, month, day)) {
                return new DateMatch(formatIso(year, month, day), m3.group(0));
            }
        }

        // 4. DD/MM/YYYY or MM/DD/YYYY
        Matcher m4 = NUMERIC_DATE.matcher(segment);
        if (m4.find()) {
            int p1 = Integer.parseInt(m4.group(1));
            int p2 = Integer.parseInt(m4.group(2));
            int year = normalizeYear(Integer.parseInt(m4.group(3)));
            int day, month;
            if (p1 > 12 && p2 <= 12) { day = p1; month = p2; }
            else if (p2 > 12 && p1 <= 12) { month = p1; day = p2; }
            else if (preferMdy) { month = p1; day = p2; }
            else { day = p1; month = p2; }
            if (isValidDate(year, month, day)) {
                return new DateMatch(formatIso(year, month, day), m4.group(0));
            }
        }

        // 5. Short MM/DD inline with explicit banking prefix
        Matcher m5 = SHORT_MM_DD.matcher(segment);
        if (m5.find()) {
            int p1 = Integer.parseInt(m5.group(1));
            int p2 = Integer.parseInt(m5.group(2));
            int year = LocalDate.now().getYear();
            int month = p1 <= 12 ? p1 : p2;
            int day = p1 <= 12 ? p2 : p1;
            if (isValidDate(year, month, day)) {
                return new DateMatch(formatIso(year, month, day), m5.group(0));
            }
        }

        return null;
    }

    private int normalizeYear(int y) {
        return y < 100 ? y + 2000 : y;
    }

    private boolean isValidDate(int year, int month, int day) {
        if (year < 1900 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) return false;
        try {
            LocalDate.of(year, month, day);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String formatIso(int year, int month, int day) {
        return LocalDate.of(year, month, day).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private AmountInfo parseAmountCell(String cell) {
        if (cell == null || cell.isBlank()) return null;
        String str = cell.trim();
        if (str.equals("-") || str.equals("--") || str.equalsIgnoreCase("nil") || str.equalsIgnoreCase("n/a")) return null;

        String hint = null;
        if (str.matches("^\\(.*\\)$") || str.startsWith("-") || str.matches("(?i).*\\bDR\\b.*")) hint = "DEBIT";
        else if (str.startsWith("+") || str.matches("(?i).*\\bCR\\b.*")) hint = "CREDIT";

        String cleaned = str
            .replaceAll("^\\((.+)\\)$", "$1")
            .replaceAll("(?i)(₹|Rs\\.?|INR|USD|EUR|GBP|\\$|DR|CR|,|\\+|\\s)", "");
        if (!cleaned.matches("-?\\d+(\\.\\d+)?")) return null;
        if (cleaned.matches("-?\\d{10,}")) return null;
        try {
            double num = Math.abs(Double.parseDouble(cleaned));
            if (!Double.isFinite(num) || num <= 0 || num > 1_000_000_000d) return null;
            return new AmountInfo(num, hint);
        } catch (Exception e) {
            return null;
        }
    }

    private Double extractAmountFromText(String text) {
        if (text == null || text.isBlank()) return 0.0;
        String sanitized = text
            .replaceAll("(?i)\\b(?:closing\\s*bal(?:ance)?|opening\\s*bal(?:ance)?|avail(?:able)?\\s*bal(?:ance)?|running\\s*bal(?:ance)?|ledger\\s*bal(?:ance)?|bal(?:ance)?)\\s*[:\\-]?\\s*(?:₹|Rs\\.?|INR|USD|\\$)?\\s*[0-9,]+(?:\\.[0-9]{1,2})?(?:\\s*(?:CR|DR))?", " ")
            .replaceAll("/[0-9]{6,}(?=/|\\s|$)", "/ ")
            .replaceAll("(?<![.\\d])\\b\\d{8,}\\b(?![.\\d])", " ");

        Pattern structured = Pattern.compile(
            "(?:[+]\\s*|[-]\\s*)?(?:₹|Rs\\.?|INR|USD|\\$)\\s*([0-9]{1,3}(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)|\\b([0-9]{1,3}(?:,[0-9]{2,3})*\\.[0-9]{1,2}|[0-9]+\\.[0-9]{1,2})\\b",
            Pattern.CASE_INSENSITIVE
        );
        Matcher m = structured.matcher(sanitized);
        List<Double> vals = new ArrayList<>();
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            if (raw == null) continue;
            raw = raw.replace(",", "");
            if (raw.matches("\\d{9,}")) continue;
            double value = Double.parseDouble(raw);
            if (value > 0 && value <= 1_000_000_000d) vals.add(value);
        }
        if (vals.size() == 1) return vals.get(0);
        if (vals.size() >= 2) return vals.get(vals.size() - 2);
        return 0.0;
    }

    /**
     * Determines whether a transaction is DEBIT (money going out / expense)
     * or CREDIT (money coming in / income).
     *
     * Rules:
     * - DR = Debit = Money going out = Expense
     * - CR = Credit = Money coming in = Income
     * - Prefer explicit Debit and Credit columns / explicit type when available
     * - Explicitly detects UPI/DR and UPI/CR in narrations and memos
     * - "Credit card" payments/bills are expenses, not income
     */
    public String detectDirection(String text, String explicitType) {
        // 1. Explicit type from column takes first priority
        if (explicitType != null && !explicitType.isBlank()) {
            String upper = explicitType.trim().toUpperCase(Locale.ROOT);
            if (upper.matches(".*\\b(?:CREDIT|CR|DEPOSIT|INCOME|INFLOW|INWARD|RECEIVED)\\b.*") ||
                upper.equals("CR") || upper.equals("CREDIT") || upper.startsWith("CR") || upper.contains("CR")) {
                if (!upper.contains("DR") && !upper.contains("DEBIT")) {
                    return "CREDIT";
                }
            }
            if (upper.matches(".*\\b(?:DEBIT|DR|WITHDRAWAL|EXPENSE|OUTFLOW|PAID)\\b.*") ||
                upper.equals("DR") || upper.equals("DEBIT") || upper.startsWith("DR") || upper.contains("DR")) {
                return "DEBIT";
            }
        }

        if (text == null || text.isBlank()) return "DEBIT";

        // 2. UPI/CR vs UPI/DR (and IMPS, NEFT, RTGS, NACH, ACH)
        // Check ALL common bank narration variants:
        // UPI/CR, UPI-CR, UPI_CR, UPI CR, UPI:CR, UPI.CR, CR/UPI, CR-UPI, UPI/CREDIT, UPI-CREDIT,
        // and UPI/REF/CR, UPI/12345/CR, UPI/REV/CR
        boolean upiCr = text.matches("(?is).*\\b(?:UPI|IMPS|NEFT|RTGS|NACH|ACH)\\s*[/\\-_:]\\s*CR\\b.*") ||
                        text.matches("(?is).*\\b(?:UPI|IMPS|NEFT|RTGS|NACH|ACH)\\s*[/\\-_:]\\s*CREDIT\\b.*") ||
                        text.matches("(?is).*\\bCR\\s*[/\\-_:]\\s*(?:UPI|IMPS|NEFT|RTGS)\\b.*") ||
                        text.matches("(?is).*\\b(?:UPI|IMPS|NEFT|RTGS)\\s+CR\\b.*") ||
                        text.matches("(?is).*\\b(?:UPI|IMPS|NEFT|RTGS)[/\\-_:][^/\\-_:\\s]+[/\\-_:]CR\\b.*");

        boolean upiDr = text.matches("(?is).*\\b(?:UPI|IMPS|NEFT|RTGS|NACH|ACH)\\s*[/\\-_:]\\s*DR\\b.*") ||
                        text.matches("(?is).*\\b(?:UPI|IMPS|NEFT|RTGS|NACH|ACH)\\s*[/\\-_:]\\s*DEBIT\\b.*") ||
                        text.matches("(?is).*\\bDR\\s*[/\\-_:]\\s*(?:UPI|IMPS|NEFT|RTGS)\\b.*") ||
                        text.matches("(?is).*\\b(?:UPI|IMPS|NEFT|RTGS)\\s+DR\\b.*") ||
                        text.matches("(?is).*\\b(?:UPI|IMPS|NEFT|RTGS)[/\\-_:][^/\\-_:\\s]+[/\\-_:]DR\\b.*");

        if (upiCr && !upiDr) return "CREDIT";
        if (upiDr && !upiCr) return "DEBIT";

        // 3. Check for standalone CR / CREDIT vs DR / DEBIT markers
        boolean isCreditCard = text.matches("(?is).*\\bcredit\\s*card\\b.*") &&
                              !text.matches("(?is).*\\b(?:refund|cashback|reversal)\\b.*");

        // Genuine CR marker (not part of "credit card")
        boolean hasCr = !isCreditCard && (
            text.matches("(?is).*(?:^|[\\s/\\-_|,\\(])(?:CR|CREDIT)(?:$|[\\s/\\-_|,\\):]).*") ||
            text.matches("(?is).*\\b(?:CR|CREDIT)\\b\\s*$") ||
            text.matches("(?is).*(?:^|\\s)\\+\\s*(?:₹|Rs\\.?|INR|\\$)?\\s*[0-9].*")
        );

        // Genuine DR marker - exclude merchant/title prefix like "DR. " or "DR " followed by doctor/hospital name
        boolean isDoctorTitle = text.matches("(?is).*\\bDR\\.?\\s+[A-Z][a-z]+.*") && !text.matches("(?is).*\\b(?:POS|ACH|ATM|WDL|WITHDRAWAL)\\b.*");
        boolean hasDr = !isDoctorTitle && (
            text.matches("(?is).*(?:^|[\\s/\\-_|,\\(])(?:DR|DEBIT|WDL)(?:$|[\\s/\\-_|,\\):]).*") ||
            text.matches("(?is).*\\b(?:DR|DEBIT)\\b\\s*$") ||
            text.matches("(?is).*(?:^|\\s)-\\s*(?:₹|Rs\\.?|INR|\\$)\\s*[0-9].*")
        );

        if (hasCr && !hasDr) return "CREDIT";
        if (hasDr && !hasCr) return "DEBIT";

        // 4. Semantic income/refund indicators
        if (text.matches("(?is).*\\b(?:salary|payroll|stipend|bonus|dividend|interest\\s+(?:paid|earned|cr|credited)|cashback|refund(?:ed)?|reimburs(?:e|ement)|credited\\s+to|deposit(?:ed)?|received\\s+from)\\b.*")) {
            return "CREDIT";
        }

        // 5. Reversals
        if (text.matches("(?is).*\\b(?:reversal|rev\\b)\\b.*") && !text.matches("(?is).*\\b(?:dr|debit)\\b.*")) {
            return "CREDIT";
        }

        return "DEBIT";
    }

    private String fingerprint(TransactionDTO dto) {
        String type = "CREDIT".equals(dto.getType()) ? "CREDIT" : "DEBIT";
        double amount = dto.getAmount() == null ? 0 : Math.round(Math.abs(dto.getAmount()) * 100.0) / 100.0;
        String date = dto.getDate() == null ? "" : dto.getDate().trim();
        String desc = dto.getRawDescription() != null ? dto.getRawDescription() : "";
        if (desc.isBlank() && dto.getMerchantName() != null) desc = dto.getMerchantName();
        desc = desc.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return date + "|" + type + "|" + String.format(Locale.US, "%.2f", amount) + "|" + desc;
    }

    private List<TransactionDTO> dedupe(List<TransactionDTO> rows) {
        List<TransactionDTO> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TransactionDTO dto : rows) {
            String key = fingerprint(dto);
            if (key.isBlank() || !seen.add(key)) continue;
            unique.add(dto);
        }
        return unique;
    }

    private TransactionDTO parseLine(String line, String overrideDate, Double overrideAmount, String overrideCategory, boolean preferMdy, String explicitType, String directionSource) {
        DateMatch dateMatch = overrideDate != null ? new DateMatch(overrideDate, "") : parseDate(line, preferMdy);
        String finalDate = dateMatch != null ? dateMatch.formatted : "";

        String textAfterDate = line;
        if (dateMatch != null && !dateMatch.rawMatched.isEmpty()) {
            textAfterDate = line.replace(dateMatch.rawMatched, " ").trim();
        }

        Double amount = overrideAmount != null ? overrideAmount : extractAmountFromText(textAfterDate);

        String textNoAmount = textAfterDate
            .replaceAll("(?i)(?:₹|Rs\\.?|INR|USD|\\$)\\s*[0-9,]+(?:\\.[0-9]{1,2})?", "")
            .replaceAll("\\b[0-9,]+\\.[0-9]{2}\\b", "")
            .replaceAll("\\b[0-9]{1,3}(?:,[0-9]{2,3})+\\b", "")
            .trim();

        MerchantRule match = null;
        for (MerchantRule rule : KNOWN_MERCHANTS) {
            if (Pattern.compile(rule.pattern).matcher(textNoAmount).find()) {
                match = rule;
                break;
            }
        }

        TransactionDTO dto = new TransactionDTO();
        dto.setId("tx-sb-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setDate(finalDate);
        dto.setRawDescription(line);
        dto.setAmount(amount);
        String directionText = directionSource != null && !directionSource.isBlank() ? directionSource : line;
        dto.setType(detectDirection(directionText, explicitType));
        dto.setStatus("settled");
        dto.setPaymentMethod("Spring Boot •••• 9012");

        if (match != null) {
            dto.setMerchantName(match.name);
            dto.setCategory(overrideCategory != null ? overrideCategory : match.category);
            dto.setSubcategory(match.subcategory);
            dto.setIsRecurring(match.isRecurring);
            dto.setTaxDeductible(match.taxDeductible);
            dto.setConfidenceScore(0.98);
            dto.setReasoning(match.reasoning);
        } else {
            String cleaned = textNoAmount
                .replaceAll("(?i)^(POS DEBIT|ACH WDL|(?:UPI|IMPS|NEFT|RTGS|NACH|ACH)[/\\-_](?:CR|DR)[/\\-_][0-9a-zA-Z*]+[/\\-_]?|(?:UPI|IMPS|NEFT|RTGS|NACH|ACH)[/\\-_]|(?:CR|DR)[/\\-_]|DEBIT CRD W/D|CHECKCARD|CHECK\\s*CRD|ELECTRONIC WITHDRAWAL|AUTOPAY|PURCHASE|BILL PMT|W/D)", "")
                .replaceAll("(?i)\\b(?:UPI|IMPS|NEFT|RTGS|NACH|ACH|CR|DR)\\b[/\\-_]?", " ")
                .replaceAll("(?i)(BANGALORE|MUMBAI|DELHI|HYDERABAD|CHENNAI|PUNE|SAN FRANCISCO|OAKLAND|SAN JOSE|NEW YORK|SEATTLE|CHICAGO|CA|WA|NY|IL|US|USA|INDIA|\\b[0-9]{5,6}\\b)", "")
                .replaceAll("(#\\d+|\\b\\d{4,}\\b|\\*+|\\d{3}-\\d{3}-\\d{4})", "")
                .replaceAll("[^a-zA-Z0-9\\s&']", " ")
                .replaceAll("\\s+", " ")
                .trim();

            if (cleaned.length() < 2) cleaned = "Merchant Transaction";

            dto.setMerchantName(cleaned);
            String fallbackCategory = overrideCategory != null ? overrideCategory : inferCategory(textNoAmount);
            dto.setCategory(fallbackCategory);
            dto.setSubcategory("Unclassified");
            dto.setIsRecurring(false);
            dto.setTaxDeductible(false);
            dto.setConfidenceScore(0.87);
            dto.setReasoning(finalDate.isEmpty()
                ? "Missing date from raw input; please review."
                : "Date and merchant extracted by Spring Boot parser.");
        }

        if ("CREDIT".equals(dto.getType())) {
            dto.setIsRecurring(false);
            dto.setTaxDeductible(false);
        }

        List<String> tags = new ArrayList<>();
        tags.add(dto.getCategory());
        tags.add(Boolean.TRUE.equals(dto.getIsRecurring()) ? "Recurring" : "Standard");
        if (finalDate.isEmpty()) tags.add("Needs Date Review");
        dto.setTags(tags);
        return dto;
    }

    private String inferCategory(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches(".*(grocer|supermarket|kirana|coffee|cafe|food|restaurant|dine).*")) return "Food & Dining";
        if (lower.matches(".*(mart|store|shop|market|retail|bazaar).*")) return "Shopping & Retail";
        if (lower.matches(".*(gas|fuel|petrol|transit|metro|cab|ride).*")) return "Transportation";
        if (lower.matches(".*(electric|water|power|broadband|telecom|recharge|bill).*")) return "Bills & Utilities";
        if (lower.matches(".*(fit|gym|health|pharmacy|clinic|med).*")) return "Health & Wellness";
        return "General & Miscellaneous";
    }

    /**
     * Extracts YYYY-MM key from ISO date string (YYYY-MM-DD).
     */
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

    /**
     * Groups transactions by their actual month and year (YYYY-MM).
     */
    public Map<String, List<TransactionDTO>> groupTransactionsByMonth(List<TransactionDTO> transactions) {
        Map<String, List<TransactionDTO>> groups = new TreeMap<>(Collections.reverseOrder());
        if (transactions == null) return groups;
        for (TransactionDTO dto : transactions) {
            String key = getMonthKey(dto.getDate());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(dto);
        }
        return groups;
    }

    public record AmountInfo(double value, String hint) {}
    public record DateMatch(String formatted, String rawMatched) {}
    public record MerchantRule(String pattern, String name, String category, String subcategory,
                               boolean isRecurring, boolean taxDeductible, String reasoning) {}
}
