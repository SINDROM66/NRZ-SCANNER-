package com.nssf.datacapture

import android.Manifest
import android.content.contentValuesOf
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var container: FrameLayout

    // UI Input References
    private lateinit var etSurname: EditText
    private lateinit var etGivenName: EditText
    private lateinit var etOtherName: EditText
    private lateinit var etSex: EditText
    private lateinit var etDob: EditText
    private lateinit var etNin: EditText
    private lateinit var etPhone: EditText
    private lateinit var btnSave: Button
    private lateinit var btnScanPhoto: Button
    private lateinit var tvScanStatus: TextView
    private lateinit var imgPreview: ImageView

    private val savedRecords = mutableListOf<CardRecord>()

    private val selectPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processImageUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabLayout = findViewById(R.id.tabLayout)
        container = findViewById(R.id.container)

        // Request Camera & Storage permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        }

        setupNativeUi()
    }

    private fun setupNativeUi() {
        // Inflate Main Form View
        val view = layoutInflater.inflate(R.layout.activity_main, container, false)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showScanMode()
                    1 -> showFormMode()
                    2 -> showRecordsMode()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        showScanMode()
    }

    private fun showScanMode() {
        container.removeAllViews()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        tvScanStatus = TextView(this).apply {
            text = "Photographs the MRZ on the back of the card and decodes it directly."
            textSize = 14f
            setPadding(0, 0, 0, 24)
        }

        imgPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 500)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }

        btnScanPhoto = Button(this).apply {
            text = "Choose / Take Photo"
            setBackgroundColor(0xFF0D4F82.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                selectPhotoLauncher.launch("image/*")
            }
        }

        layout.addView(tvScanStatus)
        layout.addView(btnScanPhoto)
        layout.addView(imgPreview)

        container.addView(layout)
    }

    private fun showFormMode(autoRecord: CardRecord? = null) {
        container.removeAllViews()
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        etSurname = createInput("SURNAME *", autoRecord?.surname ?: "", layout)
        etGivenName = createInput("GIVEN NAME(S) *", autoRecord?.givenName ?: "", layout)
        etOtherName = createInput("OTHER NAME", autoRecord?.otherName ?: "", layout)
        etSex = createInput("SEX (Male / Female) *", autoRecord?.sex ?: "Male", layout)
        etDob = createInput("DATE OF BIRTH (YYYY-MM-DD) *", autoRecord?.dateOfBirth ?: "", layout)
        etNin = createInput("NATIONAL ID NUMBER (NIN) *", autoRecord?.nin ?: "", layout)
        etPhone = createInput("PHONE NUMBER *", autoRecord?.phoneNumber ?: "", layout)

        btnSave = Button(this).apply {
            text = "Save Record (100% Offline)"
            setBackgroundColor(0xFF0A7044.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                saveRecordFromInputs()
            }
        }
        layout.addView(btnSave)

        scrollView.addView(layout)
        container.addView(scrollView)
    }

    private fun createInput(label: String, initialVal: String, parent: LinearLayout): EditText {
        val tv = TextView(this).apply {
            text = label
            textSize = 12f
            setPadding(0, 16, 0, 4)
        }
        val et = EditText(this).apply {
            setText(initialVal)
            textSize = 14f
        }
        parent.addView(tv)
        parent.addView(et)
        return et
    }

    private fun showRecordsMode() {
        container.removeAllViews()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val tvHeader = TextView(this).apply {
            text = "Saved Offline Member Records (${savedRecords.size})"
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvHeader)

        val btnExport = Button(this).apply {
            text = "Export Records to CSV File"
            setBackgroundColor(0xFF0D4F82.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { exportRecordsToCsv() }
        }
        layout.addView(btnExport)

        val listView = ListView(this)
        val items = savedRecords.map { "${it.surname} ${it.givenName} - NIN: ${it.nin} (${it.phoneNumber})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        listView.adapter = adapter
        layout.addView(listView)

        container.addView(layout)
    }

    private fun processImageUri(uri: Uri) {
        try {
            imgPreview.setImageURI(uri)
            imgPreview.visibility = View.VISIBLE
            tvScanStatus.text = "Processing image on-device with ML Kit..."

            val image = InputImage.fromFilePath(this, uri)

            // Step 1: Try Native Google ML Kit Barcode Reader (PDF417)
            val scanner = BarcodeScanning.getClient()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    var foundRecord: CardRecord? = null
                    for (barcode in barcodes) {
                        val raw = barcode.rawValue
                        val parsed = UgandaIdParser.parseBarcodePayload(raw)
                        if (parsed != null) {
                            foundRecord = parsed
                            break
                        }
                    }

                    if (foundRecord != null) {
                        tvScanStatus.text = "Barcode Decoded Successfully!"
                        showFormMode(foundRecord)
                    } else {
                        // Step 2: Fallback to Google ML Kit Text Recognizer for 3-Line MRZ OCR
                        processMrzText(image)
                    }
                }
                .addOnFailureListener {
                    processMrzText(image)
                }

        } catch (e: Exception) {
            Log.e("NSSF_Scanner", "Image process error", e)
            tvScanStatus.text = "Error processing image: ${e.message}"
        }
    }

    private fun processMrzText(image: InputImage) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val lines = visionText.textBlocks.flatMap { block -> block.lines.map { it.text } }
                val parsedRecord = UgandaIdParser.parseMrzLines(lines)

                if (parsedRecord != null) {
                    tvScanStatus.text = "MRZ Decoded Successfully!"
                    showFormMode(parsedRecord)
                } else {
                    tvScanStatus.text = "Could not decode MRZ/Barcode. Please enter details manually."
                    showFormMode()
                }
            }
            .addOnFailureListener { e ->
                tvScanStatus.text = "OCR Failed: ${e.message}. Please enter details manually."
                showFormMode()
            }
    }

    private fun saveRecordFromInputs() {
        val phone = etPhone.text.toString().trim()
        if (phone.isEmpty()) {
            Toast.makeText(this, "❗ Phone Number is mandatory!", Toast.LENGTH_SHORT).show()
            return
        }

        val record = CardRecord(
            surname = etSurname.text.toString().trim(),
            givenName = etGivenName.text.toString().trim(),
            otherName = etOtherName.text.toString().trim(),
            sex = etSex.text.toString().trim(),
            dateOfBirth = etDob.text.toString().trim(),
            nin = etNin.text.toString().trim(),
            phoneNumber = phone
        )

        savedRecords.add(record)
        Toast.makeText(this, "✅ Record Saved Offline!", Toast.LENGTH_SHORT).show()
        tabLayout.getTabAt(2)?.select()
    }

    private fun exportRecordsToCsv() {
        if (savedRecords.isEmpty()) {
            Toast.makeText(this, "No records to export.", Toast.LENGTH_SHORT).show()
            return
        }

        val csvHeader = "SURNAME,GIVEN_NAME,OTHER_NAME,SEX,DATE_OF_BIRTH,NATIONALITY,NIN,PHONE_NUMBER,SOURCE\n"
        val csvBody = savedRecords.joinToString("\n") {
            "\"${it.surname}\",\"${it.givenName}\",\"${it.otherName}\",\"${it.sex}\",\"${it.dateOfBirth}\",\"UGA\",\"${it.nin}\",\"${it.phoneNumber}\",\"${it.source}\""
        }

        val content = csvHeader + csvBody
        val fileName = "nssf_member_records_${System.currentTimeMillis()}.csv"

        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
        FileOutputStream(file).use { it.write(content.toByteArray()) }

        Toast.makeText(this, "📄 CSV Exported to: ${file.name}", Toast.LENGTH_LONG).show()
    }
}
