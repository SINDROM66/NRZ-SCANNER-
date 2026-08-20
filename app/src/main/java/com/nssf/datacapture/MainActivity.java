package com.nssf.datacapture;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.material.tabs.TabLayout;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MainActivity v3.0
 * Integrated with MrzRepairEngine for bounded check-digit repair dialogs.
 */
public class MainActivity extends AppCompatActivity implements MrzRepairEngine.RepairCallback {

    private static final String TAG = "NSSF_MRZ";
    private static final int PERMISSION_REQUEST_CODE = 101;

    private TabLayout tabLayout;

    // View Containers
    private View sectionScan;
    private View sectionForm;
    private View sectionRecords;

    // Scan Mode UI
    private TextView tvScanInstruction;
    private Button btnChoosePhoto;
    private ImageView imgPreview;

    // Form Inputs
    private EditText etSurname;
    private EditText etGivenName;
    private EditText etOtherName;
    private EditText etSex;
    private EditText etDob;
    private EditText etNin;
    private EditText etPhone;
    private EditText etCardNumber;
    private TextView tvValidationStatus;
    private Button btnSaveRecord;

    // Records Mode UI
    private TextView tvRecordsCount;
    private Button btnExportCsv;
    private ListView lvRecords;

    private final List<CardRecord> savedRecords = new ArrayList<>();

    // Background executor for ML Kit OCR processing
    private ExecutorService cameraExecutor;
    private TextRecognizer textRecognizer;
    private MrzRepairEngine repairEngine;

    // Camera capture
    private Uri currentPhotoUri;

    private final ActivityResultLauncher<Intent> photoPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) processImageUriForMrz(uri);
                }
            });

    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (success && currentPhotoUri != null) {
                    processImageUriForMrz(currentPhotoUri);
                }
            });

    private final ActivityResultLauncher<String> createDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
                if (uri != null) exportRecordsToCsvUri(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraExecutor = Executors.newSingleThreadExecutor();
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        repairEngine = new MrzRepairEngine(this, this);

        bindViews();
        setupTabLayout();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CODE);
        }
    }

    private void bindViews() {
        tabLayout = findViewById(R.id.tabLayout);
        sectionScan = findViewById(R.id.sectionScan);
        sectionForm = findViewById(R.id.sectionForm);
        sectionRecords = findViewById(R.id.sectionRecords);

        tvScanInstruction = findViewById(R.id.tvScanInstruction);
        btnChoosePhoto = findViewById(R.id.btnChoosePhoto);
        imgPreview = findViewById(R.id.imgPreview);

        etSurname = findViewById(R.id.etSurname);
        etGivenName = findViewById(R.id.etGivenName);
        etOtherName = findViewById(R.id.etOtherName);
        etSex = findViewById(R.id.etSex);
        etDob = findViewById(R.id.etDob);
        etNin = findViewById(R.id.etNin);
        etPhone = findViewById(R.id.etPhone);
        etCardNumber = findViewById(R.id.etCardNumber);
        tvValidationStatus = findViewById(R.id.tvValidationStatus);
        btnSaveRecord = findViewById(R.id.btnSaveRecord);

        tvRecordsCount = findViewById(R.id.tvRecordsCount);
        btnExportCsv = findViewById(R.id.btnExportCsv);
        lvRecords = findViewById(R.id.lvRecords);

        btnChoosePhoto.setOnClickListener(v -> showImageSourceDialog());
        btnSaveRecord.setOnClickListener(v -> saveRecordFromInputs());
        btnExportCsv.setOnClickListener(v -> showExportDialog());
    }

    private void showImageSourceDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Select Image Source")
            .setItems(new String[]{"📷 Camera", "🖼️ Gallery"}, (dialog, which) -> {
                if (which == 0) launchCamera();
                else launchGallery();
            })
            .show();
    }

    private void launchCamera() {
        try {
            File photoFile = createImageFile();
            currentPhotoUri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", photoFile);
            cameraLauncher.launch(currentPhotoUri);
        } catch (IOException e) {
            Toast.makeText(this, "Error creating image file", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        photoPickerLauncher.launch(intent);
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "MRZ_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    private void setupTabLayout() {
        tabLayout.removeAllTabs();
        tabLayout.addTab(tabLayout.newTab().setText("Scan MRZ"));
        tabLayout.addTab(tabLayout.newTab().setText("Manual Form"));
        tabLayout.addTab(tabLayout.newTab().setText("Records"));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                switch (tab.getPosition()) {
                    case 0: showSection(sectionScan); break;
                    case 1: showSection(sectionForm); break;
                    case 2: showSection(sectionRecords); updateRecordsView(); break;
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        showSection(sectionScan);
    }

    private void showSection(View targetSection) {
        sectionScan.setVisibility(View.GONE);
        sectionForm.setVisibility(View.GONE);
        sectionRecords.setVisibility(View.GONE);
        targetSection.setVisibility(View.VISIBLE);
    }

    private void processImageUriForMrz(Uri uri) {
        imgPreview.setImageURI(uri);
        imgPreview.setVisibility(View.VISIBLE);
        tvScanInstruction.setText("Reading MRZ text on-device with Google ML Kit...");

        cameraExecutor.execute(() -> {
            try {
                InputImage image = InputImage.fromFilePath(this, uri);
                textRecognizer.process(image)
                    .addOnSuccessListener(cameraExecutor, new OnSuccessListener<Text>() {
                        @Override
                        public void onSuccess(Text visionText) {
                            String text = visionText.getText();
                            List<String> lines = Arrays.asList(text.split("\n"));
                            CardRecord parsedRecord = UgandaIdParser.parseMrzLines(lines);

                            runOnUiThread(() -> {
                                if (parsedRecord != null) {
                                    handleParsedRecord(parsedRecord, lines);
                                } else {
                                    tvScanInstruction.setText("Could not decode MRZ. Please enter details manually.");
                                    showSection(sectionForm);
                                    selectTab(1);
                                }
                            });
                        }
                    })
                    .addOnFailureListener(cameraExecutor, new OnFailureListener() {
                        @Override public void onFailure(@NonNull Exception e) {
                            Log.e(TAG, "MRZ OCR Failure", e);
                            runOnUiThread(() -> {
                                tvScanInstruction.setText("MRZ OCR Error. Please enter manually.");
                                showSection(sectionForm);
                                selectTab(1);
                            });
                        }
                    });
            } catch (Exception e) {
                Log.e(TAG, "Image process error", e);
                runOnUiThread(() -> tvScanInstruction.setText("Error: " + e.getMessage()));
            }
        });
    }

    private void handleParsedRecord(CardRecord record, List<String> rawLines) {
        switch (record.validationConfidence) {
            case HIGH:
                tvScanInstruction.setText("✅ MRZ Decoded — All check digits valid!");
                fillFormFields(record);
                break;
            case MEDIUM:
                tvScanInstruction.setText("⚠️ MRZ Partial — Review suggested repair");
                String line1 = extractLine1(rawLines);
                String line2 = extractLine2(rawLines);
                repairEngine.attemptRepair(record, line1, line2);
                break;
            case REJECT:
                tvScanInstruction.setText("❌ MRZ Checksum Failed — Verify manually");
                fillFormFields(record);
                break;
        }
    }

    @Override
    public void onRepairConfirmed(CardRecord repairedRecord) {
        fillFormFields(repairedRecord);
        tvScanInstruction.setText("✅ Repair applied — All check digits now valid!");
    }

    @Override
    public void onRepairCancelled() {
        showSection(sectionForm);
        selectTab(1);
    }

    private String extractLine1(List<String> lines) {
        for (String l : lines) {
            String clean = l.trim().replaceAll("\\s+", "").toUpperCase();
            if (clean.startsWith("IDUGA") || clean.contains("CM") || clean.contains("CF")) {
                return clean.length() >= 30 ? clean.substring(0, 30) : padTo30(clean);
            }
        }
        return "";
    }

    private String extractLine2(List<String> lines) {
        for (String l : lines) {
            String clean = l.trim().replaceAll("\\s+", "").toUpperCase();
            if (clean.matches("^\\d{6}.*UGA.*")) {
                return clean.length() >= 30 ? clean.substring(0, 30) : padTo30(clean);
            }
        }
        return "";
    }

    private String padTo30(String s) {
        if (s == null) return "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<";
        if (s.length() >= 30) return s.substring(0, 30);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 30) sb.append('<');
        return sb.toString();
    }

    private void selectTab(int position) {
        TabLayout.Tab tab = tabLayout.getTabAt(position);
        if (tab != null) tab.select();
    }

    private void fillFormFields(CardRecord record) {
        if (record == null) return;
        etSurname.setText(record.surname);
        etGivenName.setText(record.givenName);
        etOtherName.setText(record.otherName);
        etSex.setText(record.sex);
        etDob.setText(record.dateOfBirth);
        etNin.setText(record.nin);
        etCardNumber.setText(record.cardNumber);

        int color;
        String msg;
        switch (record.validationConfidence) {
            case HIGH:
                msg = "✅ MRZ Validated (All check digits pass)";
                color = Color.parseColor("#4CAF50");
                break;
            case MEDIUM:
                msg = "⚠️ MRZ Partial (1 check digit failed — review fields)";
                color = Color.parseColor("#FF9800");
                break;
            default:
                msg = "❌ MRZ Checksum Failed — Verify Details Manually";
                color = Color.parseColor("#F44336");
                break;
        }
        tvValidationStatus.setText(msg);
        tvValidationStatus.setTextColor(color);

        showSection(sectionForm);
        selectTab(1);
    }

    private void updateRecordsView() {
        tvRecordsCount.setText("Saved Offline Member Records (" + savedRecords.size() + ")");
        List<String> items = new ArrayList<>();
        for (CardRecord r : savedRecords) {
            String badge = (r.validationConfidence == UgandaIdParser.ValidationConfidence.HIGH) ? "✓" :
                    (r.validationConfidence == UgandaIdParser.ValidationConfidence.MEDIUM) ? "~" : "✗";
            items.add(badge + " " + r.surname + " " + r.givenName + " - NIN: " + r.nin + " (" + r.phoneNumber + ")");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        lvRecords.setAdapter(adapter);
    }

    private void saveRecordFromInputs() {
        String phone = etPhone.getText().toString().trim();
        if (phone.isEmpty()) {
            Toast.makeText(this, "❗ Phone Number is mandatory!", Toast.LENGTH_SHORT).show();
            return;
        }

        CardRecord record = new CardRecord(
            etSurname.getText().toString().trim(),
            etGivenName.getText().toString().trim(),
            etOtherName.getText().toString().trim(),
            etSex.getText().toString().trim(),
            etDob.getText().toString().trim(),
            etNin.getText().toString().trim(),
            etCardNumber.getText().toString().trim(),
            phone,
            "Native Google ML Kit MRZ OCR"
        );

        savedRecords.add(record);
        Toast.makeText(this, "✅ Record Saved Offline!", Toast.LENGTH_SHORT).show();
        selectTab(2);
    }

    private void showExportDialog() {
        if (savedRecords.isEmpty()) {
            Toast.makeText(this, "No records to export.", Toast.LENGTH_SHORT).show();
            return;
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        createDocumentLauncher.launch("nssf_member_records_" + timeStamp + ".csv");
    }

    private void exportRecordsToCsvUri(Uri uri) {
        StringBuilder csv = new StringBuilder("SURNAME,GIVEN_NAME,OTHER_NAME,SEX,DATE_OF_BIRTH,NATIONALITY,NIN,CARD_NUMBER,PHONE_NUMBER,SOURCE,VALIDATION_STATUS\n");
        for (CardRecord r : savedRecords) {
            String vLabel = (r.validationConfidence == UgandaIdParser.ValidationConfidence.HIGH) ? "HIGH" :
                    (r.validationConfidence == UgandaIdParser.ValidationConfidence.MEDIUM) ? "MEDIUM" : "REJECT";
            csv.append(escapeCsv(r.surname)).append(",");
            csv.append(escapeCsv(r.givenName)).append(",");
            csv.append(escapeCsv(r.otherName)).append(",");
            csv.append(escapeCsv(r.sex)).append(",");
            csv.append(escapeCsv(r.dateOfBirth)).append(",");
            csv.append("\"UGA\"").append(",");
            csv.append(escapeCsv(r.nin)).append(",");
            csv.append(escapeCsv(r.cardNumber)).append(",");
            csv.append(escapeCsv(r.phoneNumber)).append(",");
            csv.append(escapeCsv(r.source)).append(",");
            csv.append(vLabel).append("\n");
        }

        try (FileOutputStream fos = (FileOutputStream) getContentResolver().openOutputStream(uri)) {
            if (fos != null) {
                fos.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "📄 CSV Exported!", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Export error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (textRecognizer != null) textRecognizer.close();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) cameraExecutor.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
