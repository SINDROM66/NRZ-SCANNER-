package com.nssf.datacapture;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * MemberRecord Entity
 * 
 * Encrypted offline storage for NSSF member pre-registration data.
 * All sensitive fields are stored in SQLCipher-encrypted SQLite.
 * 
 * Retention: Auto-purge records older than 30 days.
 */
@Entity(tableName = "member_records")
public class MemberRecord {

    @PrimaryKey(autoGenerate = true)
    public int id;

    @ColumnInfo(name = "surname")
    public String surname;

    @ColumnInfo(name = "given_name")
    public String givenName;

    @ColumnInfo(name = "other_name")
    public String otherName;

    @ColumnInfo(name = "sex")
    public String sex;

    @ColumnInfo(name = "date_of_birth")
    public String dateOfBirth;

    @ColumnInfo(name = "nin")
    public String nin;

    @ColumnInfo(name = "card_number")
    public String cardNumber;

    @ColumnInfo(name = "phone_number")
    public String phoneNumber;

    @ColumnInfo(name = "expiry_date")
    public String expiryDate;

    @ColumnInfo(name = "source")
    public String source;

    @ColumnInfo(name = "validation_confidence")
    public String validationConfidence;

    @ColumnInfo(name = "validation_failures")
    public int validationFailures;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "sync_status")
    public String syncStatus; // "PENDING", "SYNCED", "FAILED"

    @ColumnInfo(name = "device_id")
    public String deviceId;

    public MemberRecord() {}

    public static MemberRecord fromCardRecord(CardRecord record, String deviceId) {
        MemberRecord mr = new MemberRecord();
        mr.surname = record.surname;
        mr.givenName = record.givenName;
        mr.otherName = record.otherName;
        mr.sex = record.sex;
        mr.dateOfBirth = record.dateOfBirth;
        mr.nin = record.nin;
        mr.cardNumber = record.cardNumber;
        mr.phoneNumber = record.phoneNumber;
        mr.expiryDate = record.expiryDate;
        mr.source = record.source;
        mr.validationConfidence = record.validationConfidence != null 
            ? record.validationConfidence.name() : "HIGH";
        mr.validationFailures = record.validationFailures;
        mr.createdAt = System.currentTimeMillis();
        mr.syncStatus = "PENDING";
        mr.deviceId = deviceId;
        return mr;
    }

    public CardRecord toCardRecord() {
        CardRecord cr = new CardRecord(
            surname, givenName, otherName, sex, dateOfBirth, nin,
            cardNumber, phoneNumber, source
        );
        cr.expiryDate = expiryDate;
        try {
            cr.validationConfidence = UgandaIdParser.ValidationConfidence.valueOf(validationConfidence);
        } catch (Exception e) {
            cr.validationConfidence = UgandaIdParser.ValidationConfidence.HIGH;
        }
        cr.validationFailures = validationFailures;
        return cr;
    }
}
