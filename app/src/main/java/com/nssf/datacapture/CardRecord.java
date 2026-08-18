package com.nssf.datacapture;

public class CardRecord {
    public String surname = "";
    public String givenName = "";
    public String otherName = "";
    public String sex = "Male";
    public String dateOfBirth = "";
    public String nin = "";
    public String cardNumber = "";
    public String phoneNumber = "";
    public String source = "Native Google ML Kit MRZ OCR";

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
