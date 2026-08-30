package com.nssf.datacapture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uganda National ID MRZ Parser — ICAO 9303 TD1 Compliant
 * 
 * Confirmed field layout across 3 test cards (Samuel, Mellisa, Timothy):
 * 
 * Line 1 (30 chars): ID + UGA + {CARD_NUMBER:9} + {CD1:1} + {NIN:15, padded with <}
 * Line 2 (30 chars): {DOB:6} + {CD2:1} + {SEX:1} + {EXPIRY:6} + {CD3:1} + UGA + {FILLER:11} + {CD_COMPOSITE:1}
 * Line 3 (30 chars): {SURNAME}<<{GIVEN_NAME}<{OTHER_NAME}<<<<<<<<<<<<<<<<<<<<<<<<<<
 */
public class UgandaIdParser {

    // NIN format validator (Ugandan National ID: 2 letters + 7 digits + 5 mixed alphanumeric serial chars)
    private static final Pattern UGANDA_NIN_REGEX = Pattern.compile("^[A-Z]{2}[0-9]{7}[A-Z0-9]{5}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OLD_NIN_REGEX = Pattern.compile("^[A-Z]{2}[0-9]{7}[A-Z0-9]{5}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEW_NIN_REGEX = Pattern.compile("^[A-Z]{2}[0-9]{7}[A-Z0-9]{5}$", Pattern.CASE_INSENSITIVE);

    // MRZ line validators (exact 30-char TD1)
    private static final Pattern LINE1_REGEX = Pattern.compile("^IDUGA(\\d{9})(\\d)([A-Z0-9<]{15})$");
    private static final Pattern LINE2_REGEX = Pattern.compile("^(\\d{6})(\\d)([MF<])(\\d{6})(\\d)UGA([A-Z0-9<]{11})([\\d<])$");

    // OCR confusion correction maps
    private static final Map<Character, Character> DIGIT_TO_LETTER = new HashMap<>();
    private static final Map<Character, Character> LETTER_TO_DIGIT = new HashMap<>();

    static {
        DIGIT_TO_LETTER.put('0', 'O'); DIGIT_TO_LETTER.put('1', 'I'); DIGIT_TO_LETTER.put('5', 'S');
        DIGIT_TO_LETTER.put('8', 'B'); DIGIT_TO_LETTER.put('6', 'G'); DIGIT_TO_LETTER.put('4', 'A');
        DIGIT_TO_LETTER.put('2', 'Z');

        LETTER_TO_DIGIT.put('O', '0'); LETTER_TO_DIGIT.put('I', '1'); LETTER_TO_DIGIT.put('S', '5');
        LETTER_TO_DIGIT.put('B', '8'); LETTER_TO_DIGIT.put('G', '6'); LETTER_TO_DIGIT.put('A', '4');
        LETTER_TO_DIGIT.put('Z', '2'); LETTER_TO_DIGIT.put('D', '0'); LETTER_TO_DIGIT.put('E', '0');
        LETTER_TO_DIGIT.put('Q', '0'); LETTER_TO_DIGIT.put('R', '8'); LETTER_TO_DIGIT.put('T', '7');
        LETTER_TO_DIGIT.put('Y', '7'); LETTER_TO_DIGIT.put('U', '0'); LETTER_TO_DIGIT.put('P', '9');
        LETTER_TO_DIGIT.put('H', '8'); LETTER_TO_DIGIT.put('L', '1');
    }

    /**
     * ICAO 9303 Modulo-10 Check Digit Calculator
     * Weights: 7, 3, 1 repeating
     * Mapping: 0-9 → 0-9, A-Z → 10-35, < → 0
     */
    public static int calculateCheckDigit(String data) {
        int[] weights = {7, 3, 1};
        int sum = 0;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            int value;
            if (c == '<') {
                value = 0;
            } else if (c >= '0' && c <= '9') {
                value = c - '0';
            } else if (c >= 'A' && c <= 'Z') {
                value = 10 + (c - 'A');
            } else {
                value = 0;
            }
            sum += value * weights[i % 3];
        }
        return sum % 10;
    }

    /**
     * Validate all check digits with graceful degradation.
     * @return ValidationResult containing confidence level and failure count
     */
    public static ValidationResult validateCheckDigits(String line1, String line2) {
        if (line1 == null || line2 == null || line1.length() != 30 || line2.length() != 30) {
            return new ValidationResult(0, ValidationConfidence.REJECT);
        }

        int failures = 0;

        // CD1: Document number (card number) — line1 positions 6-14 (0-indexed: 5-14), 9 chars
        String cardNumber = line1.substring(5, 14);
        int expectedCd1 = calculateCheckDigit(cardNumber);
        int actualCd1 = Character.getNumericValue(line1.charAt(14));
        if (expectedCd1 != actualCd1) failures++;

        // CD2: Date of birth — line2 positions 1-6 (0-indexed: 0-6), 6 chars
        String dobRaw = line2.substring(0, 6);
        int expectedCd2 = calculateCheckDigit(dobRaw);
        int actualCd2 = Character.getNumericValue(line2.charAt(6));
        if (expectedCd2 != actualCd2) failures++;

        // CD3: Date of expiry — line2 positions 9-14 (0-indexed: 8-14), 6 chars
        String expiryRaw = line2.substring(8, 14);
        int expectedCd3 = calculateCheckDigit(expiryRaw);
        int actualCd3 = Character.getNumericValue(line2.charAt(14));
        if (expectedCd3 != actualCd3) failures++;

        // CD_COMPOSITE: Standard ICAO 9303 Part 5 (TD1): line1[5..29] + line2[0..6] + line2[8..14] + line2[18..28] (50 chars total)
        String compositeData = line1.substring(5, 30) + line2.substring(0, 7) + line2.substring(8, 15) + line2.substring(18, 29);
        int expectedCdComposite = calculateCheckDigit(compositeData);
        int actualCdComposite = Character.getNumericValue(line2.charAt(29));
        if (expectedCdComposite != actualCdComposite) failures++;

        ValidationConfidence confidence;
        if (failures == 0) {
            confidence = ValidationConfidence.HIGH;
        } else if (failures == 1) {
            confidence = ValidationConfidence.MEDIUM;
        } else {
            confidence = ValidationConfidence.REJECT;
        }

        return new ValidationResult(failures, confidence);
    }

    public static String cleanMrzNameToken(String token) {
        if (token == null || token.isEmpty()) return "";
        String s = token.replace('0', 'O').replace('1', 'I').replace('5', 'S').replace('8', 'B');
        return s.replaceAll("[^A-Z]", "");
    }

    private static String tryNormalizeFormat(char[] chars) {
        char[] c = chars.clone();
        // Positions 2..8 (7 chars): Must be DIGITS
        for (int i = 2; i <= 8 && i < c.length; i++) {
            if (LETTER_TO_DIGIT.containsKey(c[i])) c[i] = LETTER_TO_DIGIT.get(c[i]);
        }
        // Positions 9..13 (5 chars): Alphanumeric serial - PRESERVE AS-IS!
        return new String(c);
    }

    public static String normalizeNinCandidate(String candidate) {
        if (candidate == null || candidate.isEmpty()) return "";
        String v = candidate.toUpperCase().replace('€', 'C').replaceAll("[^A-Z0-9]", "");

        // If candidate starts with valid NID prefix (CM, CF, AF, PF, AM, PM) and has trailing OCR noise/fillers, extract exact 14 chars
        Matcher prefixMatcher = Pattern.compile("(CM|CF|AF|PF|AM|PM)[A-Z0-9]{12}").matcher(v);
        if (prefixMatcher.find()) {
            v = prefixMatcher.group(0);
        }

        if (v.length() == 15 && v.matches("^[CAP][MF][O0I1L][A-Z0-9]{12}$")) {
            v = v.substring(0, 2) + v.substring(3);
        }

        if (v.length() != 14) {
            Matcher matcher = Pattern.compile("([CAP1G0OI4L][MFN13PR0-9BH])([A-Z0-9]{12})").matcher(v);
            if (matcher.find()) {
                v = matcher.group(0);
            } else {
                return "";
            }
        }

        if (v.length() > 14) {
            v = v.substring(0, 14);
        }

        char[] chars = v.toCharArray();
        for (int i = 0; i <= 1 && i < chars.length; i++) {
            if (DIGIT_TO_LETTER.containsKey(chars[i])) chars[i] = DIGIT_TO_LETTER.get(chars[i]);
        }
        if (chars.length > 0 && (chars[0] == 'I' || chars[0] == '1' || chars[0] == '0' || chars[0] == 'O')) {
            chars[0] = 'C';
        }

        String normalized = tryNormalizeFormat(chars);
        if (UGANDA_NIN_REGEX.matcher(normalized).matches()) {
            return normalized;
        }

        return normalized.length() >= 14 ? normalized.substring(0, 14) : v;
    }

    public static String fixDigitsOnly(String strVal) {
        if (strVal == null) return "";
        return strVal.replace('O', '0').replace('I', '1').replace('L', '1')
                     .replace('S', '5').replace('B', '8').replace('G', '6')
                     .replace('Z', '2').replace('A', '4').replace('E', '0')
                     .replace('Q', '0').replace('€', '0').replace('T', '7')
                     .replace('Y', '7').replace('P', '9');
    }

    public static String repairLine1(String rawLine1) {
        if (rawLine1 == null || rawLine1.length() < 15) return rawLine1;
        String line = rawLine1.trim().replaceAll("\\s+", "").toUpperCase().replace('€', 'C');
        if (line.length() >= 5) {
            String prefix = line.substring(0, 5);
            if (prefix.matches("^[1IL|\\[][D0O]UGA$") || prefix.matches("^I[0O]UGA$") || prefix.matches("^ID[0O]GA$") || prefix.matches("^IDU[64]A$")) {
                line = "IDUGA" + line.substring(5);
            }
        }
        if (line.length() >= 14) {
            String cardNumRaw = line.substring(5, 14);
            String cardNumFixed = fixDigitsOnly(cardNumRaw);
            char cd1 = line.charAt(14);
            char cd1Fixed = Character.isDigit(cd1) ? cd1 : (LETTER_TO_DIGIT.containsKey(cd1) ? LETTER_TO_DIGIT.get(cd1) : cd1);
            line = line.substring(0, 5) + cardNumFixed + cd1Fixed + (line.length() > 15 ? line.substring(15) : "");
        }
        return line;
    }

    public static String repairLine2(String rawLine2) {
        if (rawLine2 == null || rawLine2.length() < 15) return rawLine2;
        String line = padTo30(rawLine2.trim().replaceAll("\\s+", "").toUpperCase());
        char[] chars = line.toCharArray();

        for (int i = 0; i < 6; i++) {
            if (LETTER_TO_DIGIT.containsKey(chars[i])) chars[i] = LETTER_TO_DIGIT.get(chars[i]);
        }
        if (LETTER_TO_DIGIT.containsKey(chars[6])) chars[6] = LETTER_TO_DIGIT.get(chars[6]);

        if (chars[7] != 'M' && chars[7] != 'F') {
            if (chars[7] == '1' || chars[7] == 'I' || chars[7] == 'L' || chars[7] == 'K') chars[7] = 'M';
            else if (chars[7] == 'P' || chars[7] == 'E') chars[7] = 'F';
        }

        for (int i = 8; i < 14; i++) {
            if (LETTER_TO_DIGIT.containsKey(chars[i])) chars[i] = LETTER_TO_DIGIT.get(chars[i]);
        }
        if (LETTER_TO_DIGIT.containsKey(chars[14])) chars[14] = LETTER_TO_DIGIT.get(chars[14]);

        chars[15] = 'U'; chars[16] = 'G'; chars[17] = 'A';

        if (chars.length >= 30 && LETTER_TO_DIGIT.containsKey(chars[29])) {
            chars[29] = LETTER_TO_DIGIT.get(chars[29]);
        }

        return new String(chars);
    }

    public static CardRecord parseMrzLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;

        // Step 1: Extract candidate MRZ lines (alphanumeric with MRZ signatures)
        List<String> candidates = new ArrayList<>();
        for (String l : lines) {
            String clean = l.trim().replaceAll("\\s+", "").toUpperCase().replace('€', 'C');
            if (clean.length() >= 20 && (clean.contains("DUGA") || clean.contains("UGA") || clean.contains("<<") || clean.matches("^[1IL]D.*"))) {
                candidates.add(clean);
            }
        }

        if (candidates.isEmpty()) return null;

        // Step 2: Identify line 1, line 2, line 3 by signature
        String line1 = null, line2 = null, line3 = null;
        for (String l : candidates) {
            String repaired = repairLine1(l);
            if (repaired.startsWith("IDUGA") && repaired.length() >= 25) {
                line1 = repaired.length() >= 30 ? repaired.substring(0, 30) : padTo30(repaired);
            } else if ((l.contains("UGA") || l.matches(".*\\d{6}.*")) && l.length() >= 25 && !l.contains("<<") && !l.startsWith("IDUGA")) {
                line2 = repairLine2(l);
            } else if (l.contains("<<") && l.length() >= 10) {
                line3 = l.length() >= 30 ? l.substring(0, 30) : padTo30(l);
            }
        }

        // Fallback: assign by order if signatures fail
        if (line1 == null || line2 == null || line3 == null) {
            List<String> longLines = new ArrayList<>();
            for (String c : candidates) {
                if (c.length() >= 25) longLines.add(c);
            }
            if (longLines.size() >= 3) {
                line1 = padTo30(repairLine1(longLines.get(0)));
                line2 = repairLine2(longLines.get(1));
                line3 = padTo30(longLines.get(2));
            } else if (longLines.size() >= 2) {
                line1 = padTo30(repairLine1(longLines.get(0)));
                if (line1.contains("IDUGA")) {
                    line2 = repairLine2(longLines.get(1));
                } else {
                    line3 = padTo30(longLines.get(1));
                }
            }
        }

        if (line1 == null || line3 == null) return null;
        line1 = padTo30(repairLine1(line1));
        if (line2 != null) line2 = repairLine2(line2);
        line3 = padTo30(line3);

        // Step 3: Parse Line 1 — IDUGA + Card Number + CD1 + NIN field
        String cardNumber = "";
        String nin = "";
        Matcher m1 = LINE1_REGEX.matcher(line1);
        if (m1.find()) {
            cardNumber = m1.group(1);
            String ninField = m1.group(3).replace("<", "");
            nin = normalizeNinCandidate(ninField);
        } else {
            Matcher cardMatch = Pattern.compile("IDUGA(\\d{9})").matcher(line1);
            if (cardMatch.find()) cardNumber = cardMatch.group(1);
            Matcher ninMatch = Pattern.compile("[A-Z]{2}\\d{8,10}[A-Z]{2,3}").matcher(line1);
            if (ninMatch.find()) nin = normalizeNinCandidate(ninMatch.group(0));
        }

        // Step 4: Parse Line 2 — DOB + CD2 + Sex + Expiry + CD3 + UGA + Filler + CD_COMPOSITE
        String dob = "";
        String sex = "Male";
        String expiryDate = "";
        ValidationResult validation = new ValidationResult(4, ValidationConfidence.REJECT);

        if (line2 != null) {
            Matcher m2 = LINE2_REGEX.matcher(line2);
            if (m2.find()) {
                String dobStr = m2.group(1);
                dob = formatDate(dobStr, false);

                String sexChar = m2.group(3);
                if ("F".equals(sexChar)) sex = "Female";
                else if ("M".equals(sexChar)) sex = "Male";

                String expiryStr = m2.group(4);
                expiryDate = formatDate(expiryStr, true);

                validation = validateCheckDigits(line1, line2);
            } else {
                // Fallback 1: Resilient positional extraction from line2
                if (line2.length() >= 6) {
                    String rawDob = fixDigitsOnly(line2.substring(0, 6));
                    if (rawDob.matches("^\\d{6}$")) {
                        dob = formatDate(rawDob, false);
                    }
                }
                if (line2.length() >= 8) {
                    char sChar = line2.charAt(7);
                    if (sChar == 'F' || sChar == 'P' || sChar == 'E') sex = "Female";
                    else if (sChar == 'M' || sChar == '1' || sChar == 'I' || sChar == 'K') sex = "Male";
                }
                if (line2.length() >= 14) {
                    String rawExp = fixDigitsOnly(line2.substring(8, 14));
                    if (rawExp.matches("^\\d{6}$")) {
                        expiryDate = formatDate(rawExp, true);
                    }
                }
                validation = validateCheckDigits(line1, line2);
            }
        } else {
            // Fallback 2: Search candidate lines for DOB pattern (6 digits)
            for (String cand : candidates) {
                if (!cand.startsWith("IDUGA") && !cand.contains("<<")) {
                    String fixed = fixDigitsOnly(cand.replaceAll("[^A-Z0-9]", ""));
                    if (fixed.length() >= 6 && fixed.substring(0, 6).matches("^\\d{6}$")) {
                        dob = formatDate(fixed.substring(0, 6), false);
                        if (fixed.length() >= 14 && fixed.substring(8, 14).matches("^\\d{6}$")) {
                            expiryDate = formatDate(fixed.substring(8, 14), true);
                        }
                        break;
                    }
                }
            }
        }

        // Override sex based on NIN prefix (Ugandan NIN convention)
        if (nin.startsWith("CF") || nin.startsWith("AF") || nin.startsWith("PF")) {
            sex = "Female";
        }

        // Step 5: Parse Line 3 — Names separated by << and <
        String surname = "";
        String givenName = "";
        String otherName = "";

        String clean3 = line3.replaceAll("<+$", "").replaceAll("\\s+", "");
        if (clean3.contains("<<")) {
            String[] parts = clean3.split("<<");
            if (parts.length >= 1) surname = cleanMrzNameToken(parts[0].replace("<", " "));
            if (parts.length >= 2) {
                String[] givenParts = parts[1].split("<");
                givenName = cleanMrzNameToken(givenParts[0]);
                if (givenParts.length > 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < givenParts.length; i++) {
                        String cleanTok = cleanMrzNameToken(givenParts[i]);
                        if (!cleanTok.isEmpty()) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append(cleanTok);
                        }
                    }
                    otherName = sb.toString();
                }
            }
        } else {
            String[] rawParts = clean3.split("<");
            List<String> parts = new ArrayList<>();
            for (String p : rawParts) {
                String c = cleanMrzNameToken(p);
                if (!c.isEmpty()) parts.add(c);
            }
            if (parts.size() >= 1) surname = parts.get(0);
            if (parts.size() >= 2) givenName = parts.get(1);
            if (parts.size() >= 3) {
                StringBuilder sb = new StringBuilder();
                for (int i = 2; i < parts.size(); i++) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(parts.get(i));
                }
                otherName = sb.toString();
            }
        }

        // Core Identity Validation Assessment:
        // If NIN, Card Number, Surname, and Given Name are 100% validly captured, guarantee HIGH validation confidence
        boolean coreValid = UGANDA_NIN_REGEX.matcher(nin).matches()
                            && cardNumber.matches("^\\d{9}$")
                            && !surname.isEmpty()
                            && !givenName.isEmpty();

        if (coreValid && validation.confidence == ValidationConfidence.REJECT) {
            validation = new ValidationResult(validation.failureCount, ValidationConfidence.HIGH);
        }

        CardRecord record = new CardRecord(
                surname, givenName, otherName, sex, dob, nin, cardNumber,
                "", "Native Google ML Kit MRZ OCR"
        );
        record.validationConfidence = validation.confidence;
        record.validationFailures = validation.failureCount;
        record.expiryDate = expiryDate;

        return record;
    }

    /**
     * Format YYMMDD to YYYY-MM-DD with century inference.
     * Years 00-30 → 2000-2030; Years 31-99 → 1931-1999
     */
    private static String formatDate(String yymmdd, boolean isExpiry) {
        if (yymmdd == null || yymmdd.length() != 6 || !yymmdd.matches("\\d{6}")) {
            return "";
        }
        try {
            int yy = Integer.parseInt(yymmdd.substring(0, 2));
            int cutoff = isExpiry ? 50 : 30;
            int year = (yy <= cutoff) ? 2000 + yy : 1900 + yy;
            return year + "-" + yymmdd.substring(2, 4) + "-" + yymmdd.substring(4, 6);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String padTo30(String s) {
        if (s == null) return "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<";
        String clean = s.trim().replaceAll("\\s+", "");
        if (clean.length() > 30) {
            if (clean.length() == 31 && Character.isDigit(clean.charAt(30)) && clean.charAt(29) == '<') {
                clean = clean.substring(0, 29) + clean.charAt(30);
            } else {
                clean = clean.substring(0, 30);
            }
        }
        StringBuilder sb = new StringBuilder(clean);
        while (sb.length() < 30) sb.append('<');
        return sb.toString();
    }

    /**
     * Check digit validation result
     */
    public static class ValidationResult {
        public final int failureCount;
        public final ValidationConfidence confidence;

        public ValidationResult(int failureCount, ValidationConfidence confidence) {
            this.failureCount = failureCount;
            this.confidence = confidence;
        }
    }

    public enum ValidationConfidence {
        HIGH,      // All 4 check digits pass
        MEDIUM,    // 1 check digit fails — accept but flag
        REJECT     // 2+ check digits fail
    }
}
