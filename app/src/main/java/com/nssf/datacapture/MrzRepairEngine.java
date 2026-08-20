package com.nssf.datacapture;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * MrzRepairEngine
 * 
 * Bounded single-character check-digit repair with user confirmation.
 * When 1 check digit fails, this engine generates candidate repairs by
 * substituting visually confusable characters (0↔O, 1↔I, etc.) at each
 * position in the failed field, then re-validates. Only candidates that
 * achieve HIGH confidence are presented to the user for manual confirmation.
 * 
 * NEVER auto-applies repairs. Silent data corruption is worse than a retry.
 */
public class MrzRepairEngine {

    private final Context context;
    private final RepairCallback callback;

    public interface RepairCallback {
        void onRepairConfirmed(CardRecord repairedRecord);
        void onRepairCancelled();
    }

    public MrzRepairEngine(Context context, RepairCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    /**
     * Attempt to repair a MEDIUM-confidence record.
     * 
     * @param record The parsed record with ValidationConfidence.MEDIUM
     * @param line1 The raw MRZ line 1 (30 chars)
     * @param line2 The raw MRZ line 2 (30 chars)
     */
    public void attemptRepair(CardRecord record, String line1, String line2) {
        if (record == null || record.validationConfidence != UgandaIdParser.ValidationConfidence.MEDIUM) {
            if (callback != null) callback.onRepairCancelled();
            return;
        }

        // Determine which check digit failed
        List<RepairCandidate> candidates = generateCandidates(line1, line2);

        if (candidates.isEmpty()) {
            Toast.makeText(context, "⚠️ Could not auto-suggest a repair. Please verify manually.", 
                Toast.LENGTH_LONG).show();
            if (callback != null) callback.onRepairCancelled();
            return;
        }

        showRepairDialog(record, candidates, line1, line2);
    }

    /**
     * Generate repair candidates by trying single-character substitutions
     * on the field that failed check digit validation.
     */
    private List<RepairCandidate> generateCandidates(String line1, String line2) {
        List<RepairCandidate> candidates = new ArrayList<>();

        if (line1 == null || line1.length() < 14 || line2 == null || line2.length() < 14) {
            return candidates;
        }

        // Test each field individually
        String[][] fields = {
            {"Card Number", line1.substring(5, Math.min(14, line1.length())), "cd1", line1, line2, "1"},
            {"Date of Birth", line2.substring(0, Math.min(6, line2.length())), "cd2", line1, line2, "2"},
            {"Expiry Date", line2.length() >= 14 ? line2.substring(8, 14) : "", "cd3", line1, line2, "2"}
        };

        for (String[] field : fields) {
            String fieldName = field[0];
            String fieldValue = field[1];
            if (fieldValue.isEmpty()) continue;
            String cdType = field[2];
            String originalLine1 = field[3];
            String originalLine2 = field[4];
            String targetLine = field[5]; // "1" = line1, "2" = line2

            char[] chars = fieldValue.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                char original = chars[i];
                List<Character> alternatives = getOcrAlternatives(original);

                for (char alt : alternatives) {
                    chars[i] = alt;
                    String repairedValue = new String(chars);

                    // Reconstruct the full line with the repaired value
                    String testLine1 = originalLine1;
                    String testLine2 = originalLine2;

                    if ("1".equals(targetLine)) {
                        testLine1 = originalLine1.substring(0, 5) + repairedValue + originalLine1.substring(14);
                    } else if ("cd2".equals(cdType)) {
                        testLine2 = repairedValue + originalLine2.substring(6);
                    } else if ("cd3".equals(cdType)) {
                        testLine2 = originalLine2.substring(0, 8) + repairedValue + originalLine2.substring(14);
                    }

                    // Re-validate
                    UgandaIdParser.ValidationResult result = 
                        UgandaIdParser.validateCheckDigits(testLine1, testLine2);

                    if (result.confidence == UgandaIdParser.ValidationConfidence.HIGH) {
                        candidates.add(new RepairCandidate(
                            fieldName,
                            i,
                            original,
                            alt,
                            repairedValue,
                            testLine1,
                            testLine2,
                            "Changed '" + original + "' to '" + alt + "' at position " + (i + 1) + " in " + fieldName
                        ));
                    }
                }
                chars[i] = original; // restore
            }
        }

        return candidates;
    }

    /**
     * Get visually confusable OCR alternatives for a character.
     */
    private List<Character> getOcrAlternatives(char c) {
        List<Character> alts = new ArrayList<>();

        switch (c) {
            case '0': alts.add('O'); break;
            case 'O': alts.add('0'); break;
            case '1': alts.add('I'); alts.add('L'); break;
            case 'I': alts.add('1'); alts.add('L'); break;
            case 'L': alts.add('1'); alts.add('I'); break;
            case '5': alts.add('S'); break;
            case 'S': alts.add('5'); break;
            case '8': alts.add('B'); break;
            case 'B': alts.add('8'); break;
            case '6': alts.add('G'); break;
            case 'G': alts.add('6'); break;
            case '4': alts.add('A'); break;
            case 'A': alts.add('4'); break;
            case '2': alts.add('Z'); break;
            case 'Z': alts.add('2'); break;
            case '7': alts.add('T'); break;
            case 'T': alts.add('7'); break;
            case '9': alts.add('P'); break;
            case 'P': alts.add('9'); break;
            case '3': alts.add('E'); break;
            case 'E': alts.add('3'); break;
        }

        return alts;
    }

    /**
     * Show the repair confirmation dialog to the user.
     */
    private void showRepairDialog(CardRecord originalRecord, 
                                   List<RepairCandidate> candidates,
                                   String line1, String line2) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("⚠️ MRZ Check Digit Warning");
        builder.setMessage("One field may have an OCR error. Please confirm the correct value:");

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        // Show original failed value
        TextView originalLabel = new TextView(context);
        originalLabel.setText("Original scan: " + getFailedFieldSummary(originalRecord, line1, line2));
        originalLabel.setTextColor(Color.GRAY);
        originalLabel.setPadding(0, 0, 0, 16);
        layout.addView(originalLabel);

        // Show top 3 candidates as buttons
        int maxCandidates = Math.min(3, candidates.size());
        for (int i = 0; i < maxCandidates; i++) {
            RepairCandidate cand = candidates.get(i);
            Button btn = new Button(context);
            btn.setText((i + 1) + ". " + cand.description);
            btn.setTag(cand);
            btn.setOnClickListener(v -> {
                RepairCandidate selected = (RepairCandidate) v.getTag();
                CardRecord repaired = UgandaIdParser.parseMrzLines(
                    java.util.Arrays.asList(selected.repairedLine1, selected.repairedLine2, line2)
                );
                if (repaired != null) {
                    repaired.validationConfidence = UgandaIdParser.ValidationConfidence.HIGH;
                    repaired.validationFailures = 0;
                    // Preserve phone from original if already entered
                    repaired.phoneNumber = originalRecord.phoneNumber;
                    Toast.makeText(context, "✅ Repair applied! All check digits pass.", 
                        Toast.LENGTH_SHORT).show();
                    if (callback != null) callback.onRepairConfirmed(repaired);
                }
            });
            layout.addView(btn);
        }

        builder.setView(layout);
        builder.setNegativeButton("Enter Manually", (dialog, which) -> {
            if (callback != null) callback.onRepairCancelled();
        });
        builder.setCancelable(false);
        builder.show();
    }

    private String getFailedFieldSummary(CardRecord record, String line1, String line2) {
        if (line1 == null || line1.length() < 15 || line2 == null || line2.length() < 15) {
            return "MRZ check digit mismatch";
        }
        StringBuilder sb = new StringBuilder();

        String cardNum = line1.substring(5, 14);
        int expectedCd1 = UgandaIdParser.calculateCheckDigit(cardNum);
        int actualCd1 = Character.getNumericValue(line1.charAt(14));
        if (expectedCd1 != actualCd1) sb.append("Card# ").append(cardNum).append(" (CD fail) ");

        String dob = line2.substring(0, 6);
        int expectedCd2 = UgandaIdParser.calculateCheckDigit(dob);
        int actualCd2 = Character.getNumericValue(line2.charAt(6));
        if (expectedCd2 != actualCd2) sb.append("DOB ").append(dob).append(" (CD fail) ");

        String expiry = line2.substring(8, 14);
        int expectedCd3 = UgandaIdParser.calculateCheckDigit(expiry);
        int actualCd3 = Character.getNumericValue(line2.charAt(14));
        if (expectedCd3 != actualCd3) sb.append("Expiry ").append(expiry).append(" (CD fail)");

        return sb.toString().trim();
    }

    /**
     * Internal data class for repair candidates.
     */
    public static class RepairCandidate {
        public final String fieldName;
        public final int position;
        public final char originalChar;
        public final char replacementChar;
        public final String repairedValue;
        public final String repairedLine1;
        public final String repairedLine2;
        public final String description;

        public RepairCandidate(String fieldName, int position, char originalChar, 
                               char replacementChar, String repairedValue,
                               String repairedLine1, String repairedLine2, 
                               String description) {
            this.fieldName = fieldName;
            this.position = position;
            this.originalChar = originalChar;
            this.replacementChar = replacementChar;
            this.repairedValue = repairedValue;
            this.repairedLine1 = repairedLine1;
            this.repairedLine2 = repairedLine2;
            this.description = description;
        }
    }
}
