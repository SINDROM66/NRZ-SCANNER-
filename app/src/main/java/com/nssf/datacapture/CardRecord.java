package com.nssf.datacapture;

import com.nssf.datacapture.UgandaIdParser.ValidationConfidence;

/**
 * CardRecord — Universal Data Model (POJO)
 *
 * Core data container carrying a member's pre-registration identity details
 * from OCR / PDF417 extraction through UI form editing, Room offline database
 * persistence, CSV export, and NIRA cloud sync.
 */
public class CardRecord {

    /**
     * Enum tracking the exact provenance/capture mechanism for audit trails.
     * Used by NIRA sync workers to determine validation strategy.
     */
    public enum CaptureSource {
        MRZ("Native Google ML Kit MRZ OCR"),
        PDF417_BARCODE("PDF417 2D Barcode"),
        MANUAL("Manual Operator Input");

        private final String label;

        CaptureSource(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    // Core Identity Fields
    public String surname = "";
    public String givenName = "";
    public String otherName = "";

    // Demographic Fields
    public String sex = "Male";
    public String dateOfBirth = "";
    public String expiryDate = "";

    // Document Fields
    public String nin = "";
    public String cardNumber = "";

    // NSSF-Specific Fields
    public String phoneNumber = "";

    // Provenance & Audit Fields
    public String source = CaptureSource.MRZ.getLabel();
    public CaptureSource captureSource = CaptureSource.MRZ;

    // MRZ Validation Metadata
    public ValidationConfidence validationConfidence = ValidationConfidence.HIGH;
    public int validationFailures = 0;

    // Default no-arg constructor
    public CardRecord() {}

    // Full constructor
    public CardRecord(String surname, String givenName, String otherName, String sex,
                      String dateOfBirth, String nin, String cardNumber,
                      String phoneNumber, String source) {
        this.surname = surname;
        this.givenName = givenName;
        this.otherName = otherName;
        this.sex = sex;
        this.dateOfBirth = dateOfBirth;
        this.nin = nin;
        this.cardNumber = cardNumber;
        this.phoneNumber = phoneNumber;
        this.source = source;
        if (source != null) {
            for (CaptureSource cs : CaptureSource.values()) {
                if (cs.getLabel().equalsIgnoreCase(source) || source.contains(cs.name())) {
                    this.captureSource = cs;
                    break;
                }
            }
        }
    }

    /**
     * Atomic helper: sets both the enum and its string label in one call.
     * Prevents captureSource and source from drifting out of sync.
     */
    public void setCaptureSource(CaptureSource src) {
        this.captureSource = src;
        this.source = src.getLabel();
    }

    /**
     * Display name for list views.
     */
    public String getDisplayName() {
        return surname + " " + givenName + " - NIN: " + nin;
    }

    /**
     * Validation badge emoji for UI lists.
     */
    public String getValidationBadge() {
        switch (validationConfidence) {
            case HIGH:   return "✓";
            case MEDIUM: return "~";
            case REJECT: return "✗";
            default:     return "?";
        }
    }

    @Override
    public String toString() {
        return "CardRecord{" +
                "surname='" + surname + '\'' +
                ", givenName='" + givenName + '\'' +
                ", nin='" + nin + '\'' +
                ", captureSource=" + captureSource +
                ", validationConfidence=" + validationConfidence +
                '}';
    }
}
