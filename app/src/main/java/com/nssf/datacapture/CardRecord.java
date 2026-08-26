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
     * Enum tracking the exact provenance/capture mechanism for audit trails
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

    // Identity & Name Fields
    public String surname = "";
    public String givenName = "";
    public String otherName = "";

    // Demographics
    public String sex = "Male";
    public String dateOfBirth = "";
    public String expiryDate = "";

    // Document Identifiers
    public String nin = "";
    public String cardNumber = "";

    // NSSF Registration Data
    public String phoneNumber = "";

    // Provenance & Telemetry Metadata
    public String source = CaptureSource.MRZ.getLabel();
    public CaptureSource captureSource = CaptureSource.MRZ;

    // Validation Metadata
    public ValidationConfidence validationConfidence = ValidationConfidence.HIGH;
    public int validationFailures = 0;

    /**
     * Default No-Arg Constructor
     */
    public CardRecord() {}

    /**
     * Primary Parameterized Constructor
     */
    public CardRecord(String surname, String givenName, String otherName, String sex,
                      String dateOfBirth, String nin, String cardNumber,
                      String phoneNumber, String source) {
        this.surname = surname != null ? surname : "";
        this.givenName = givenName != null ? givenName : "";
        this.otherName = otherName != null ? otherName : "";
        this.sex = sex != null ? sex : "Male";
        this.dateOfBirth = dateOfBirth != null ? dateOfBirth : "";
        this.nin = nin != null ? nin : "";
        this.cardNumber = cardNumber != null ? cardNumber : "";
        this.phoneNumber = phoneNumber != null ? phoneNumber : "";
        this.source = source != null ? source : CaptureSource.MRZ.getLabel();
        this.captureSource = deriveCaptureSource(this.source);
    }

    /**
     * Helper to set capture source and label atomically
     */
    public void setCaptureSource(CaptureSource src) {
        if (src != null) {
            this.captureSource = src;
            this.source = src.getLabel();
        }
    }

    private CaptureSource deriveCaptureSource(String srcLabel) {
        if (srcLabel == null) return CaptureSource.MRZ;
        if (srcLabel.contains("Barcode") || srcLabel.contains("PDF417")) return CaptureSource.PDF417_BARCODE;
        if (srcLabel.contains("Manual") || srcLabel.contains("Input")) return CaptureSource.MANUAL;
        return CaptureSource.MRZ;
    }
}
