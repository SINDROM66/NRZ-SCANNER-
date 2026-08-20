package com.nssf.datacapture;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

public class MainActivity extends AppCompatActivity {

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
    private ExecutorService cameraExecutor;
    private TextRecognizer recognizer;

    private final List<CardRecord> savedRecords = new ArrayList<>();

    private final ActivityResultLauncher<Intent> photoPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        processImageUriForMrz(uri);
                    }
                }
            });

    private Uri cameraPhotoUri;

    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (success && cameraPhotoUri != null) {
                    processImageUriForMrz(cameraPhotoUri);
                }
            });

    private final ActivityResultLauncher<String> createCsvLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
                if (uri != null) {
                    writeCsvToUri(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraExecutor = Executors.newSingleThreadExecutor();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        bindViews();
        setupTabLayout();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) {
            recognizer.close();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
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
        btnExportCsv.setOnClickListener(v -> exportRecordsToCsv());
    }

    private void showImageSourceDialog() {
        String[] options = {"📷 Take Photo with Camera", "🖼️ Choose from Gallery"};
        new AlertDialog.Builder(this)
                .setTitle("Select MRZ Image Source")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
                            return;
                        }
                        try {
                            File photoFile = File.createTempFile("mrz_camera_", ".jpg", getExternalFilesDir(Environment.DIRECTORY_PICTURES));
                            cameraPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
                            cameraLauncher.launch(cameraPhotoUri);
                        } catch (Exception e) {
                            Toast.makeText(this, "Failed to launch camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("image/*");
                        photoPickerLauncher.launch(intent);
                    }
                })
                .show();
    }

    private void setupTabLayout() {
        tabLayout.removeAllTabs();
        tabLayout.addTab(tabLayout.newTab().setText("Scan MRZ"));
        tabLayout.addTab(tabLayout.newTab().setText("Manual Form"));
        tabLayout.addTab(tabLayout.newTab().setText("Records"));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                switch (tab.getPosition()) {
                    case 0:
                        showSection(sectionScan);
                        break;
                    case 1:
                        showSection(sectionForm);
                        break;
                    case 2:
                        showSection(sectionRecords);
                        updateRecordsView();
                        break;
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

    private void fillFormFields(CardRecord record) {
        if (record == null) return;
        etSurname.setText(record.surname);
        etGivenName.setText(record.givenName);
        etOtherName.setText(record.otherName);
        etSex.setText(record.sex);
        etDob.setText(record.dateOfBirth);
        etNin.setText(record.nin);
        if (etCardNumber != null) etCardNumber.setText(record.cardNumber);
        if (tvValidationStatus != null) {
            String badge = record.validationConfidence == UgandaIdParser.ValidationConfidence.HIGH ? "🟢 VALID (HIGH)" :
                           record.validationConfidence == UgandaIdParser.ValidationConfidence.MEDIUM ? "🟠 REVIEW (MEDIUM)" : "🔴 REJECTED";
            tvValidationStatus.setText("Validation: " + badge + " | Failures: " + record.validationFailures);
        }
        showSection(sectionForm);
        TabLayout.Tab tab = tabLayout.getTabAt(1);
        if (tab != null) tab.select();
    }

    private void updateRecordsView() {
        tvRecordsCount.setText("Saved Offline Member Records (" + savedRecords.size() + ")");
        List<String> items = new ArrayList<>();
        for (CardRecord r : savedRecords) {
            items.add(r.surname + " " + r.givenName + " - NIN: " + r.nin + " (" + r.phoneNumber + ")");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        lvRecords.setAdapter(adapter);
    }

    private void processImageUriForMrz(Uri uri) {
        try {
            imgPreview.setImageURI(uri);
            imgPreview.setVisibility(View.VISIBLE);
            tvScanInstruction.setText("Reading MRZ text on-device with Google ML Kit...");

            cameraExecutor.execute(() -> {
                try {
                    InputImage image = InputImage.fromFilePath(this, uri);
                    recognizer.process(image)
                            .addOnSuccessListener(cameraExecutor, new OnSuccessListener<Text>() {
                                @Override
                                public void onSuccess(Text visionText) {
                                    String text = visionText.getText();
                                    List<String> lines = Arrays.asList(text.split("\n"));
                                    CardRecord parsedRecord = UgandaIdParser.parseMrzLines(lines);

                                    runOnUiThread(() -> {
                                        if (parsedRecord != null) {
                                            tvScanInstruction.setText("MRZ Decoded Successfully!");
                                            fillFormFields(parsedRecord);
                                        } else {
                                            tvScanInstruction.setText("Could not decode MRZ text. Please enter details manually.");
                                            showSection(sectionForm);
                                            TabLayout.Tab tab = tabLayout.getTabAt(1);
                                            if (tab != null) tab.select();
                                        }
                                    });
                                }
                            })
                            .addOnFailureListener(cameraExecutor, new OnFailureListener() {
                                @Override
                                public void onFailure(@NonNull Exception e) {
                                    Log.e("NSSF_MRZ", "MRZ OCR Failure", e);
                                    runOnUiThread(() -> {
                                        tvScanInstruction.setText("MRZ OCR Error: " + e.getMessage() + ". Please enter details manually.");
                                        showSection(sectionForm);
                                        TabLayout.Tab tab = tabLayout.getTabAt(1);
                                        if (tab != null) tab.select();
                                    });
                                }
                            });
                } catch (Exception e) {
                    Log.e("NSSF_MRZ", "Image process error", e);
                    runOnUiThread(() -> {
                        tvScanInstruction.setText("Error processing image: " + e.getMessage());
                    });
                }
            });

        } catch (Exception e) {
            Log.e("NSSF_MRZ", "Image process error", e);
            tvScanInstruction.setText("Error opening photo: " + e.getMessage());
        }
    }

    private void saveRecordFromInputs() {
        String surname = etSurname.getText().toString().trim();
        String givenName = etGivenName.getText().toString().trim();
        String otherName = etOtherName.getText().toString().trim();
        String sex = etSex.getText().toString().trim();
        String dob = etDob.getText().toString().trim();
        String nin = etNin.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();

        if (surname.isEmpty() || givenName.isEmpty() || nin.isEmpty()) {
            Toast.makeText(this, "Please ensure Surname, Given Name, and NIN are filled.", Toast.LENGTH_SHORT).show();
            return;
        }

        CardRecord record = new CardRecord(
                surname,
                givenName,
                otherName,
                sex,
                dob,
                nin,
                "",          // cardNumber
                phone,
                "Native Google ML Kit MRZ OCR"
        );

        savedRecords.add(record);
        Toast.makeText(this, "✅ Record Saved Offline!", Toast.LENGTH_SHORT).show();
        TabLayout.Tab tab = tabLayout.getTabAt(2);
        if (tab != null) tab.select();
    }

    private void exportRecordsToCsv() {
        if (savedRecords.isEmpty()) {
            Toast.makeText(this, "No records to export.", Toast.LENGTH_SHORT).show();
            return;
        }

        String fileName = "nssf_member_records_" + System.currentTimeMillis() + ".csv";
        try {
            createCsvLauncher.launch(fileName);
        } catch (Exception e) {
            Toast.makeText(this, "Error launching download picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void writeCsvToUri(Uri uri) {
        StringBuilder csv = new StringBuilder("SCHEMA_VERSION,SURNAME,GIVEN_NAME,OTHER_NAME,SEX,DATE_OF_BIRTH,NATIONALITY,NIN,CARD_NUMBER,PHONE_NUMBER,VALIDATION_STATUS,SOURCE\n");
        for (CardRecord r : savedRecords) {
            String status = r.validationConfidence == UgandaIdParser.ValidationConfidence.HIGH ? "HIGH" :
                           r.validationConfidence == UgandaIdParser.ValidationConfidence.MEDIUM ? "MEDIUM" : "REJECT";
            csv.append("\"2.0.0\",");
            csv.append("\"").append(r.surname).append("\",");
            csv.append("\"").append(r.givenName).append("\",");
            csv.append("\"").append(r.otherName).append("\",");
            csv.append("\"").append(r.sex).append("\",");
            csv.append("\"").append(r.dateOfBirth).append("\",");
            csv.append("\"UGA\",");
            csv.append("\"").append(r.nin).append("\",");
            csv.append("\"").append(r.cardNumber).append("\",");
            csv.append("\"").append(r.phoneNumber).append("\",");
            csv.append("\"").append(status).append("\",");
            csv.append("\"").append(r.source).append("\"\n");
        }

        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os != null) {
                os.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "📄 CSV Saved to Downloads!", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error saving CSV: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
