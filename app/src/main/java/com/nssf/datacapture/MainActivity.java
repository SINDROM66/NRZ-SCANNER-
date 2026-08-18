package com.nssf.datacapture;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.tabs.TabLayout;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TabLayout tabLayout;
    private FrameLayout container;

    // Form inputs
    private EditText etSurname;
    private EditText etGivenName;
    private EditText etOtherName;
    private EditText etSex;
    private EditText etDob;
    private EditText etNin;
    private EditText etPhone;
    private Button btnSave;
    private Button btnScanPhoto;
    private TextView tvScanStatus;
    private ImageView imgPreview;

    private final List<CardRecord> savedRecords = new ArrayList<>();

    private final ActivityResultLauncher<String> selectPhotoLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    processImageUriForMrz(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tabLayout = findViewById(R.id.tabLayout);
        container = findViewById(R.id.container);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
        }

        setupNativeUi();
    }

    private void setupNativeUi() {
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                if (pos == 0) showScanMode();
                else if (pos == 1) showFormMode(null);
                else if (pos == 2) showRecordsMode();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        showScanMode();
    }

    private void showScanMode() {
        container.removeAllViews();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        tvScanStatus = new TextView(this);
        tvScanStatus.setText("Photographs the MRZ on the back of the card and decodes it directly. Ensure the text is clear.");
        tvScanStatus.setTextSize(14f);
        tvScanStatus.setPadding(0, 0, 0, 24);

        imgPreview = new ImageView(this);
        imgPreview.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 500));
        imgPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imgPreview.setVisibility(View.GONE);

        btnScanPhoto = new Button(this);
        btnScanPhoto.setText("Choose / Take Photo");
        btnScanPhoto.setBackgroundColor(0xFF0D4F82);
        btnScanPhoto.setTextColor(0xFFFFFFFF);
        btnScanPhoto.setOnClickListener(v -> selectPhotoLauncher.launch("image/*"));

        layout.addView(tvScanStatus);
        layout.addView(btnScanPhoto);
        layout.addView(imgPreview);

        container.addView(layout);
    }

    private void showFormMode(CardRecord autoRecord) {
        container.removeAllViews();
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        etSurname = createInput("SURNAME *", autoRecord != null ? autoRecord.surname : "", layout);
        etGivenName = createInput("GIVEN NAME(S) *", autoRecord != null ? autoRecord.givenName : "", layout);
        etOtherName = createInput("OTHER NAME", autoRecord != null ? autoRecord.otherName : "", layout);
        etSex = createInput("SEX (Male / Female) *", autoRecord != null ? autoRecord.sex : "Male", layout);
        etDob = createInput("DATE OF BIRTH (YYYY-MM-DD) *", autoRecord != null ? autoRecord.dateOfBirth : "", layout);
        etNin = createInput("NATIONAL ID NUMBER (NIN) *", autoRecord != null ? autoRecord.nin : "", layout);
        etPhone = createInput("PHONE NUMBER *", autoRecord != null ? autoRecord.phoneNumber : "", layout);

        btnSave = new Button(this);
        btnSave.setText("Save Record (100% Offline)");
        btnSave.setBackgroundColor(0xFF0A7044);
        btnSave.setTextColor(0xFFFFFFFF);
        btnSave.setOnClickListener(v -> saveRecordFromInputs());
        layout.addView(btnSave);

        scrollView.addView(layout);
        container.addView(scrollView);
    }

    private EditText createInput(String label, String initialVal, LinearLayout parent) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12f);
        tv.setPadding(0, 16, 0, 4);

        EditText et = new EditText(this);
        et.setText(initialVal);
        et.setTextSize(14f);

        parent.addView(tv);
        parent.addView(et);
        return et;
    }

    private void showRecordsMode() {
        container.removeAllViews();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        TextView tvHeader = new TextView(this);
        tvHeader.setText("Saved Offline Member Records (" + savedRecords.size() + ")");
        tvHeader.setTextSize(16f);
        tvHeader.setPadding(0, 0, 0, 16);
        layout.addView(tvHeader);

        Button btnExport = new Button(this);
        btnExport.setText("Export Records to CSV File");
        btnExport.setBackgroundColor(0xFF0D4F82);
        btnExport.setTextColor(0xFFFFFFFF);
        btnExport.setOnClickListener(v -> exportRecordsToCsv());
        layout.addView(btnExport);

        ListView listView = new ListView(this);
        List<String> items = new ArrayList<>();
        for (CardRecord r : savedRecords) {
            items.add(r.surname + " " + r.givenName + " - NIN: " + r.nin + " (" + r.phoneNumber + ")");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        listView.setAdapter(adapter);
        layout.addView(listView);

        container.addView(layout);
    }

    private void processImageUriForMrz(Uri uri) {
        try {
            imgPreview.setImageURI(uri);
            imgPreview.setVisibility(View.VISIBLE);
            tvScanStatus.setText("Reading MRZ text on-device with Google ML Kit...");

            InputImage image = InputImage.fromFilePath(this, uri);
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    String text = visionText.getText();
                    List<String> lines = Arrays.asList(text.split("\n"));
                    CardRecord parsedRecord = UgandaIdParser.parseMrzLines(lines);

                    if (parsedRecord != null) {
                        tvScanStatus.setText("MRZ Decoded Successfully!");
                        showFormMode(parsedRecord);
                    } else {
                        tvScanStatus.setText("Could not decode MRZ text. Please enter details manually.");
                        showFormMode(null);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("NSSF_MRZ", "MRZ OCR Failure", e);
                    tvScanStatus.setText("MRZ OCR Error: " + e.getMessage() + ". Please enter details manually.");
                    showFormMode(null);
                });

        } catch (Exception e) {
            Log.e("NSSF_MRZ", "Image process error", e);
            tvScanStatus.setText("Error processing image: " + e.getMessage());
        }
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

        StringBuilder csv = new StringBuilder("SURNAME,GIVEN_NAME,OTHER_NAME,SEX,DATE_OF_BIRTH,NATIONALITY,NIN,PHONE_NUMBER,SOURCE\n");
        for (CardRecord r : savedRecords) {
            csv.append("\"").append(r.surname).append("\",")
               .append("\"").append(r.givenName).append("\",")
               .append("\"").append(r.otherName).append("\",")
               .append("\"").append(r.sex).append("\",")
               .append("\"").append(r.dateOfBirth).append("\",")
               .append("\"UGA\",")
               .append("\"").append(r.nin).append("\",")
               .append("\"").append(r.phoneNumber).append("\",")
               .append("\"").append(r.source).append("\"\n");
        }

        String fileName = "nssf_member_records_" + System.currentTimeMillis() + ".csv";
        File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(csv.toString().getBytes());
            Toast.makeText(this, "📄 CSV Exported to: " + file.getName(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error exporting CSV: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
