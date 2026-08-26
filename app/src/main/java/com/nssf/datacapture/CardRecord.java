package com.nssf.datacapture;

import com.nssf.datacapture.UgandaIdParser.ValidationConfidence;

public class CardRecord {
    public enum Source {
        MRZ("Native Google ML Kit MRZ OCR"),
        PDF417_BARCODE("PDF417 2D Barcode"),
        MANUAL("Manual Operator Input");

        private final String label;
        Source(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public String surname = "";
    public String givenName = "";
    public String otherName = "";
    public String sex = "Male";
    public String dateOfBirth = "";
    public String nin = "";
    public String cardNumber = "";
    public String phoneNumber = "";
    public String source = Source.MRZ.getLabel();
    public Source captureSource = Source.MRZ;

    // MRZ validation metadata
    public ValidationConfidence validationConfidence = ValidationConfidence.HIGH;
    public int validationFailures = 0;
    public String expiryDate = "";

    public CardRecord() {}

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
    }
}
