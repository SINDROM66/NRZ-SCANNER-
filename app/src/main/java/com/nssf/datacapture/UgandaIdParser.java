package com.nssf.datacapture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UgandaIdParser {

    private static final Pattern OLD_NIN_REGEX = Pattern.compile("^[A-Z]{2}[0-9]{9}[A-Z]{3}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEW_NIN_REGEX = Pattern.compile("^[A-Z]{2}[0-9]{10}[A-Z]{2}$", Pattern.CASE_INSENSITIVE);

    private static final Map<Character, Character> DIGIT_TO_LETTER = new HashMap<>();
    private static final Map<Character, Character> LETTER_TO_DIGIT = new HashMap<>();

    static {
        DIGIT_TO_LETTER.put('0', 'O'); DIGIT_TO_LETTER.put('1', 'I'); DIGIT_TO_LETTER.put('5', 'S');
        DIGIT_TO_LETTER.put('8', 'B'); DIGIT_TO_LETTER.put('6', 'G'); DIGIT_TO_LETTER.put('4', 'A'); DIGIT_TO_LETTER.put('2', 'Z');

        LETTER_TO_DIGIT.put('O', '0'); LETTER_TO_DIGIT.put('I', '1'); LETTER_TO_DIGIT.put('S', '5');
        LETTER_TO_DIGIT.put('B', '8'); LETTER_TO_DIGIT.put('G', '6'); LETTER_TO_DIGIT.put('A', '4');
        LETTER_TO_DIGIT.put('Z', '2'); LETTER_TO_DIGIT.put('D', '0'); LETTER_TO_DIGIT.put('E', '0');
        LETTER_TO_DIGIT.put('Q', '0'); LETTER_TO_DIGIT.put('R', '8'); LETTER_TO_DIGIT.put('T', '7');
        LETTER_TO_DIGIT.put('Y', '7'); LETTER_TO_DIGIT.put('U', '0'); LETTER_TO_DIGIT.put('P', '9');
        LETTER_TO_DIGIT.put('H', '8'); LETTER_TO_DIGIT.put('L', '1');
    }

    public static String cleanMrzNameToken(String token) {
        if (token == null || token.isEmpty()) return "";
        String s = token.replace('0', 'O').replace('1', 'I').replace('5', 'S').replace('8', 'B');
        return s.replaceAll("[^A-Z]", "");
    }

    private static String tryNormalizeOldFormat(char[] chars) {
        char[] c = chars.clone();
        for (int i = 2; i < 11 && i < c.length; i++) {
            if (LETTER_TO_DIGIT.containsKey(c[i])) c[i] = LETTER_TO_DIGIT.get(c[i]);
        }
        for (int i = 11; i < 14 && i < c.length; i++) {
            if (DIGIT_TO_LETTER.containsKey(c[i])) c[i] = DIGIT_TO_LETTER.get(c[i]);
        }
        return new String(c);
    }

    private static String tryNormalizeNewFormat(char[] chars) {
        char[] c = chars.clone();
        for (int i = 2; i < 12 && i < c.length; i++) {
            if (LETTER_TO_DIGIT.containsKey(c[i])) c[i] = LETTER_TO_DIGIT.get(c[i]);
        }
        for (int i = 12; i < 14 && i < c.length; i++) {
            if (DIGIT_TO_LETTER.containsKey(c[i])) c[i] = DIGIT_TO_LETTER.get(c[i]);
        }
        return new String(c);
    }

    public static String normalizeNinCandidate(String candidate) {
        if (candidate == null || candidate.isEmpty()) return "";
        String v = candidate.toUpperCase().replace('€', 'C').replaceAll("[^A-Z0-9]", "");

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

        char[] chars = v.toCharArray();
        for (int i = 0; i <= 1 && i < chars.length; i++) {
            if (DIGIT_TO_LETTER.containsKey(chars[i])) chars[i] = DIGIT_TO_LETTER.get(chars[i]);
        }
        if (chars.length > 0 && (chars[0] == 'I' || chars[0] == '1' || chars[0] == 'O' || chars[0] == '0')) {
            chars[0] = 'C';
        }

        String newCand = tryNormalizeNewFormat(chars);
        if (NEW_NIN_REGEX.matcher(newCand).matches()) return newCand;

        String oldCand = tryNormalizeOldFormat(chars);
        if (OLD_NIN_REGEX.matcher(oldCand).matches()) return oldCand;

        return newCand.length() == 14 ? newCand : oldCand;
    }

    public static CardRecord parseMrzLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;

        List<String> candidates = new ArrayList<>();
        for (String l : lines) {
            String clean = l.trim().replaceAll("\\s+", "").toUpperCase().replace('€', 'C');
            if (clean.contains("UGA") || clean.contains("<") || clean.contains("CM0") || clean.contains("CF0") || clean.contains("IDUGA")) {
                candidates.add(clean);
            }
        }

        if (candidates.isEmpty()) return null;

        String line1 = null, line2 = null, line3 = null;
        for (String l : candidates) {
            if (l.contains("IDUGA") || (l.length() >= 25 && (l.contains("CM") || l.contains("CF") || l.startsWith("UGA")))) {
                line1 = l;
            } else if (l.matches(".*\\d{6}[MF\\d]\\d{6}UGA.*") || (l.length() >= 20 && l.contains("UGA"))) {
                line2 = l;
            } else if (l.contains("<<") || (l.length() >= 15 && l.contains("<"))) {
                line3 = l;
            }
        }

        if (line1 == null || line3 == null) {
            if (candidates.size() >= 3) {
                line1 = candidates.get(0); line2 = candidates.get(1); line3 = candidates.get(2);
            } else if (candidates.size() >= 2) {
                line1 = candidates.get(0); line3 = candidates.get(1);
            } else {
                return null;
            }
        }

        String cardNumber = "", nin = "";
        Pattern m1Pattern = Pattern.compile("IDUGA(\\d{9})\\d([A-Z0-9<]{14,15})");
        Matcher matcher1 = m1Pattern.matcher(line1);
        if (matcher1.find()) {
            cardNumber = matcher1.group(1);
            nin = normalizeNinCandidate(matcher1.group(2).replace("<", ""));
        } else {
            Matcher cardMatch = Pattern.compile("\\d{9,10}").matcher(line1);
            if (cardMatch.find()) cardNumber = cardMatch.group(0);
            Matcher ninMatch = Pattern.compile("[A-Z]{2}\\d{8,9}[A-Z0-9]{3,4}").matcher(line1);
            if (ninMatch.find()) nin = normalizeNinCandidate(ninMatch.group(0));
        }

        String dob = "", sex = "Male";
        if (line2 != null) {
            Pattern m2Pattern = Pattern.compile("(\\d{6})\\d([MF<])(\\d{6})\\dUGA");
            Matcher matcher2 = m2Pattern.matcher(line2);
            if (matcher2.find()) {
                String dobStr = matcher2.group(1);
                if (dobStr.length() == 6) {
                    try {
                        int yy = Integer.parseInt(dobStr.substring(0, 2));
                        int year = (yy <= 30) ? 2000 + yy : 1900 + yy;
                        dob = year + "-" + dobStr.substring(2, 4) + "-" + dobStr.substring(4, 6);
                    } catch (Exception ignored) {}
                }
                String sexChar = matcher2.group(2);
                if ("F".equals(sexChar)) sex = "Female";
                else if ("M".equals(sexChar)) sex = "Male";
            }
        }

        if (nin.startsWith("CF") || nin.startsWith("AF") || nin.startsWith("PF")) {
            sex = "Female";
        }

        String surname = "", givenName = "", otherName = "";
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

        return new CardRecord(surname, givenName, otherName, sex, dob, nin, cardNumber, "", "Native Google ML Kit MRZ OCR");
    }
}
