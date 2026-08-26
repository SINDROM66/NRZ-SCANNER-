package com.nssf.datacapture;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * MrzBarcodeFallback
 * 
 * Parallel PDF417 barcode scanner for Ugandan National ID cards.
 * Ugandan NID backs contain a high-density PDF417 barcode above the MRZ lines
 * that holds encrypted/encoded biometric and demographic data.
 * 
 * Architecture:
 *   1. Camera frame is fed to BOTH BarcodeScanner AND TextRecognizer in parallel
 *   2. If barcode decodes successfully → parse payload, skip MRZ pipeline
 *   3. If barcode fails or payload is encrypted → fall back to MRZ OCR
 */
public class MrzBarcodeFallback {

    private static final String TAG = "NSSF_BARCODE";

    private final BarcodeScanner barcodeScanner;
    private final ExecutorService executor;

    public interface BarcodeCallback {
        void onBarcodeDecoded(CardRecord record);
        void onBarcodeFailed(String reason);
    }

    public MrzBarcodeFallback(ExecutorService executor) {
        this.executor = executor;
        this.barcodeScanner = BarcodeScanning.getClient(
            new com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_PDF417)
                .build()
        );
    }

    /**
     * Attempt to decode a PDF417 barcode from a camera frame or bitmap.
     * 
     * @param bitmap The camera frame or captured image
     * @param rotationDegrees Rotation (0, 90, 180, 270)
     * @param callback Result callback
     */
    public void process(Bitmap bitmap, int rotationDegrees, BarcodeCallback callback) {
        InputImage image = InputImage.fromBitmap(bitmap, rotationDegrees);

        barcodeScanner.process(image)
            .addOnSuccessListener(executor, barcodes -> {
                CardRecord record = parseBarcodes(barcodes);
                if (record != null) {
                    Log.i(TAG, "PDF417 decoded successfully");
                    callback.onBarcodeDecoded(record);
                } else {
                    callback.onBarcodeFailed("PDF417 detected but payload unreadable");
                }
            })
            .addOnFailureListener(executor, e -> {
                Log.e(TAG, "Barcode scan failed", e);
                callback.onBarcodeFailed(e.getMessage());
            });
    }

    /**
     * Parse PDF417 barcodes into CardRecord.
     */
    private CardRecord parseBarcodes(List<Barcode> barcodes) {
        for (Barcode barcode : barcodes) {
            String rawValue = barcode.getRawValue();
            if (rawValue == null || rawValue.isEmpty()) continue;

            Log.d(TAG, "PDF417 raw length: " + rawValue.length());

            CardRecord plain = tryParsePlainText(rawValue);
            if (plain != null) return plain;

            if (isAsn1Der(rawValue)) {
                Log.w(TAG, "PDF417 payload is ASN.1 DER encoded — cannot decode without LDS keys");
                return null;
            }
        }
        return null;
    }

    private CardRecord tryParsePlainText(String raw) {
        if (!raw.contains("|")) return null;

        String[] parts = raw.split("\\|");
        if (parts.length < 6) return null;

        try {
            CardRecord record = new CardRecord();
            record.surname = parts[0].trim();
            record.givenName = parts[1].trim();
            record.otherName = parts.length > 2 ? parts[2].trim() : "";
            record.dateOfBirth = parts[3].trim();
            record.sex = parts[4].trim();
            record.nin = parts[5].trim();
            if (parts.length > 6) record.cardNumber = parts[6].trim();
            if (parts.length > 7) record.expiryDate = parts[7].trim();
            record.setCaptureSource(CardRecord.CaptureSource.PDF417_BARCODE);
            record.validationConfidence = UgandaIdParser.ValidationConfidence.HIGH;
            return record;
        } catch (Exception e) {
            Log.e(TAG, "Plain text parse failed", e);
            return null;
        }
    }

    private boolean isAsn1Der(String raw) {
        if (raw.length() < 2) return false;
        char first = raw.charAt(0);
        return first == '\u0030' || first == '\u0077' || first == '\u0060';
    }

    public void close() {
        barcodeScanner.close();
    }
}
