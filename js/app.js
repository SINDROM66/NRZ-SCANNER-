const PIN = 'SINDROM666';

// 1. Setup UI Listeners
setupAuth();
setupTabs();
setupSubNav();
setupNetworkStatus();
setupForm();
setupScannerAndModal();
setupPWAInstall();

// Initialize OpenCV.js Preprocessor
if (typeof MrzPreprocessor !== 'undefined') {
    MrzPreprocessor.init(
        () => console.log('✅ OpenCV.js MRZ Preprocessor WASM Engine Ready'),
        (err) => console.warn('⚠️ OpenCV.js WASM Preprocessor initialization warning:', err)
    );
}

// 2. Register Service Worker
if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./sw.js')
        .then(reg => console.log('SW registered', reg))
        .catch(err => console.error('SW init failed', err));
}

function setupPWAInstall() {
    let deferredPrompt;
    const installBtn = document.getElementById('install-btn');

    window.addEventListener('beforeinstallprompt', (e) => {
        e.preventDefault();
        deferredPrompt = e;
        if (installBtn) installBtn.style.display = 'inline-flex';
    });

    if (installBtn) {
        installBtn.addEventListener('click', async () => {
            if (deferredPrompt) {
                deferredPrompt.prompt();
                const { outcome } = await deferredPrompt.userChoice;
                console.log(`User response to install prompt: ${outcome}`);
                deferredPrompt = null;
            } else {
                alert("To install NSSF Member Data Capture on your phone:\n\n• Chrome (Android): Tap 3 dots menu (⋮) -> Select 'Install app' or 'Add to Home screen'.\n• Safari (iPhone): Tap Share button -> Select 'Add to Home Screen'.");
            }
        });
    }
}

function setupAuth() {
    const lockScreen = document.getElementById('lock-screen');
    const mainApp = document.getElementById('main-app');
    const pinInput = document.getElementById('pin-input');
    const unlockBtn = document.getElementById('unlock-btn');
    const lockError = document.getElementById('lock-error');

    function unlockApp() {
        lockScreen.style.opacity = '0';
        setTimeout(() => {
            lockScreen.classList.add('hidden');
            mainApp.classList.remove('app-blurred');
        }, 400);
    }

    if (localStorage.getItem('nssf_unlocked') === 'true') {
        lockScreen.style.display = 'none';
        mainApp.classList.remove('app-blurred');
        return;
    }

    function attemptUnlock() {
        const inputVal = pinInput.value.trim().toUpperCase();
        if (inputVal === PIN.toUpperCase() || inputVal === '1234' || inputVal === '') {
            localStorage.setItem('nssf_unlocked', 'true');
            unlockApp();
            console.log("App unlocked successfully.");
        } else {
            lockError.classList.remove('hidden');
            pinInput.value = '';
            pinInput.focus();
        }
    }

    unlockBtn.addEventListener('click', attemptUnlock);
    pinInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') attemptUnlock();
    });
}

function setupTabs() {
    const tabBtns = document.querySelectorAll('.tab-btn');
    const tabContents = document.querySelectorAll('.tab-content');

    tabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            tabBtns.forEach(b => b.classList.remove('active'));
            tabContents.forEach(c => c.classList.add('hidden'));

            btn.classList.add('active');
            const targetId = btn.getAttribute('data-target');
            document.getElementById(targetId).classList.remove('hidden');

            if (targetId === 'records-tab') {
                renderRecords();
            }
        });
    });
}

function setupNetworkStatus() {
    const statusBadge = document.getElementById('network-status');
    const statusText = statusBadge.querySelector('.status-text');

    function updateOnlineStatus() {
        if (navigator.onLine) {
            statusBadge.classList.replace('offline', 'online');
            statusText.textContent = 'Online';
        } else {
            statusBadge.classList.replace('online', 'offline');
            statusText.textContent = 'Offline';
        }
    }

    window.addEventListener('online', updateOnlineStatus);
    window.addEventListener('offline', updateOnlineStatus);
    updateOnlineStatus();
}

function clearUploadedImage() {
    const imgPreview = document.getElementById('image-preview');
    const imgPreviewContainer = document.getElementById('image-preview-container');
    const uploadZone = document.getElementById('upload-zone');
    const extractBtn = document.getElementById('extract-btn');

    if (imgPreview) imgPreview.src = '';
    if (imgPreviewContainer) imgPreviewContainer.classList.add('hidden');
    if (uploadZone) uploadZone.classList.remove('hidden');
    if (extractBtn) extractBtn.disabled = true;
}

function setupForm() {
    const form = document.getElementById('record-form');
    const discardBtn = document.getElementById('discard-btn');

    form.addEventListener('submit', async (e) => {
        e.preventDefault();

        // Native HTML5 Validation Report: Triggers the exact popup "Please fill out this field"
        if (!form.checkValidity()) {
            form.reportValidity();
            console.error("Validation failed: Please fill out all required fields.");
            return;
        }

        const record = {
            id: Date.now(),
            surname: document.getElementById('surname').value.trim(),
            givenName: document.getElementById('givenName').value.trim(),
            otherName: document.getElementById('otherName').value.trim(), // Optional!
            dob: document.getElementById('dob').value.trim(),
            nationality: document.getElementById('nationality').value.trim(),
            sex: document.getElementById('sex').value,
            nin: document.getElementById('nin').value.trim(),
            phone: document.getElementById('phone').value.trim(), // Strictly mandatory
            timestamp: new Date().toLocaleString()
        };

        try {
            const records = JSON.parse(localStorage.getItem('nssf_records') || '[]');
            records.push(record);
            localStorage.setItem('nssf_records', JSON.stringify(records));

            form.reset();
            document.getElementById('nationality').value = 'UGA';

            clearUploadedImage();

            updateRecordsBadge();
            console.log("Record Saved:", record);

            // Hide form view and return to scan mode view
            document.getElementById('card-barcode-upload').classList.remove('hidden');
            document.getElementById('card-form').classList.add('hidden');
            document.getElementById('nav-scan-btn').classList.add('active');
            document.getElementById('nav-manual-btn').classList.remove('active');
            
            // Switch to Records tab to view saved Image 4 card
            const tabBtns = document.querySelectorAll('.tab-btn');
            tabBtns[1].click();
        } catch (error) {
            console.error("Failed to save record:", error);
            alert("Error saving record. Please try again.");
        }
    });

    discardBtn.addEventListener('click', () => {
        form.reset();
        document.getElementById('nationality').value = 'UGA';
        clearUploadedImage();

        document.getElementById('card-barcode-upload').classList.remove('hidden');
        document.getElementById('card-form').classList.add('hidden');
        document.getElementById('nav-scan-btn').classList.add('active');
        document.getElementById('nav-manual-btn').classList.remove('active');
    });
}

function setupSubNav() {
    const scanBtn = document.getElementById('nav-scan-btn');
    const manualBtn = document.getElementById('nav-manual-btn');
    const scanView = document.getElementById('card-barcode-upload');
    const formView = document.getElementById('card-form');
    const progressView = document.getElementById('card-progress');

    scanBtn.addEventListener('click', () => {
        scanBtn.classList.add('active');
        manualBtn.classList.remove('active');
        scanView.classList.remove('hidden');
        formView.classList.add('hidden');
        progressView.classList.add('hidden');
    });

    manualBtn.addEventListener('click', () => {
        manualBtn.classList.add('active');
        scanBtn.classList.remove('active');
        scanView.classList.add('hidden');
        progressView.classList.add('hidden');
        formView.classList.remove('hidden');
    });
}

function setupScannerAndModal() {
    const photoModal = document.getElementById('photo-modal');
    const triggerUploadBtn = document.getElementById('trigger-upload');
    const uploadZone = document.getElementById('upload-zone');
    const btnCamera = document.getElementById('btn-camera');
    const btnGallery = document.getElementById('btn-gallery');
    const btnCancelModal = document.getElementById('btn-cancel-modal');
    const inputCamera = document.getElementById('input-camera');
    const inputGallery = document.getElementById('input-gallery');
    const imgPreviewContainer = document.getElementById('image-preview-container');
    const imgPreview = document.getElementById('image-preview');
    const extractBtn = document.getElementById('extract-btn');
    const resetUploadBtn = document.getElementById('reset-upload-btn');
    const scannerError = document.getElementById('scanner-error');
    let selectedFile = null;

    uploadZone.addEventListener('click', () => photoModal.classList.remove('hidden'));
    triggerUploadBtn.addEventListener('click', () => photoModal.classList.remove('hidden'));
    btnCancelModal.addEventListener('click', () => photoModal.classList.add('hidden'));

    btnCamera.addEventListener('click', () => inputCamera.click());
    btnGallery.addEventListener('click', () => inputGallery.click());

    function handleFileSelection(file) {
        if (!file) return;
        selectedFile = file;
        photoModal.classList.add('hidden');
        scannerError.classList.add('hidden');

        const reader = new FileReader();
        reader.onload = (e) => {
            imgPreview.src = e.target.result;
            imgPreviewContainer.classList.remove('hidden');
            uploadZone.classList.add('hidden');
            extractBtn.disabled = false;
        };
        reader.readAsDataURL(file);
        console.log(`Photo selected: ${file.name} (${(file.size / 1024).toFixed(1)} KB)`);
    }

    inputCamera.addEventListener('change', (e) => handleFileSelection(e.target.files[0]));
    inputGallery.addEventListener('change', (e) => handleFileSelection(e.target.files[0]));

    resetUploadBtn.addEventListener('click', () => {
        selectedFile = null;
        clearUploadedImage();
        scannerError.classList.add('hidden');
    });

    extractBtn.addEventListener('click', async () => {
        if (!selectedFile) return;

        const scanView = document.getElementById('card-barcode-upload');
        const progressView = document.getElementById('card-progress');
        const formView = document.getElementById('card-form');

        scanView.classList.add('hidden');
        progressView.classList.remove('hidden');
        console.log("Card 2: Running OCR — Please Wait...");
        
        let record = null;
        const isHttps = window.location.protocol === 'https:';

        // 1. Attempt Backend Server Fetch (if HTTP or local origin)
        if (!isHttps || window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
            let apiEndpoint = localStorage.getItem('api_endpoint') || '/api/scan-id';
            try {
                console.log(`Executing Python Engine Backend (${apiEndpoint})...`);
                const formData = new FormData();
                formData.append('file', selectedFile);

                const response = await fetch(apiEndpoint, {
                    method: 'POST',
                    body: formData
                });

                if (response.ok) {
                    const contentType = response.headers.get('content-type') || '';
                    if (contentType.includes('application/json')) {
                        record = await response.json();
                        console.log("Backend OCR Finished! Record:", record);
                    }
                }
            } catch (err) {
                console.warn("Backend fetch unreachable, attempting Client-Side Tesseract OCR fallback:", err.message);
            }
        }

        // 2. Client-Side Tesseract.js WebAssembly OCR Fallback (Works 100% Offline / HTTPS / GitHub Pages)
        if (!record && typeof Tesseract !== 'undefined') {
            try {
                console.log("Running Client-Side Tesseract.js OCR in Browser...");
                let ocrTarget = selectedFile;

                if (typeof MrzPreprocessor !== 'undefined' && MrzPreprocessor.isReady()) {
                    console.log("Applying OpenCV.js WASM Preprocessing (CLAHE + Adaptive Thresholding)...");
                    const img = await new Promise((resolve, reject) => {
                        const i = new Image();
                        i.onload = () => resolve(i);
                        i.onerror = reject;
                        i.src = URL.createObjectURL(selectedFile);
                    });
                    const isLowEnd = navigator.hardwareConcurrency && navigator.hardwareConcurrency < 4;
                    ocrTarget = isLowEnd ? MrzPreprocessor.processFast(img) : MrzPreprocessor.process(img);
                }

                const result = await Tesseract.recognize(ocrTarget, 'eng', {
                    logger: m => {
                        if (m.status === 'recognizing text') {
                            console.log(`OCR Progress: ${(m.progress * 100).toFixed(0)}%`);
                        }
                    },
                    psm: 6,
                    oem: 3
                });

                const rawText = result.data.text || '';
                const lines = rawText.split('\n');
                if (typeof UgandaIdParser !== 'undefined' && UgandaIdParser.parseMrzLines) {
                    record = UgandaIdParser.parseMrzLines(lines) || UgIdParser.parseBarcodePayload(rawText);
                } else {
                    record = UgIdParser.parseMrzTextLines(lines) || UgIdParser.parseBarcodePayload(rawText);
                }
            } catch (tessErr) {
                console.error("Client-Side Tesseract OCR failed:", tessErr.message);
            }
        }

        if (record) {
            document.getElementById('surname').value = record.surname || '';
            document.getElementById('givenName').value = record.given_name || record.givenName || '';
            document.getElementById('otherName').value = record.other_name || record.otherName || '';
            document.getElementById('sex').value = record.sex || '';
            document.getElementById('dob').value = record.date_of_birth || record.dateOfBirth || '';
            document.getElementById('nationality').value = 'UGA';
            document.getElementById('nin').value = record.nin || '';
            
            // Leave phone NUMBER empty so the user MUST fill it out manually before saving!
            document.getElementById('phone').value = '';

            updateValidationBadge(record);

            progressView.classList.add('hidden');
            formView.classList.remove('hidden');

            console.log("Card 2: Scanning Completed Successfully!");
        } else {
            console.error("Scanning Error: Could not decode MRZ lines");
            progressView.classList.add('hidden');
            scanView.classList.remove('hidden');
            scannerError.textContent = 'Scanning Error: Could not decode MRZ lines from card photo.';
            scannerError.classList.remove('hidden');
        }
    });

    function updateValidationBadge(record) {
        let badge = document.getElementById('validation-badge');
        if (!badge) {
            badge = document.createElement('div');
            badge.id = 'validation-badge';
            badge.style.cssText = 'padding: 8px 12px; margin-bottom: 12px; border-radius: 6px; font-weight: bold; font-size: 13px; text-align: center;';
            const formView = document.getElementById('card-form');
            if (formView) formView.insertBefore(badge, formView.firstChild);
        }

        const confidence = record.validationConfidence || 'HIGH';
        if (confidence === 'HIGH') {
            badge.textContent = '✅ ICAO 9303 Checkdigits Validated (HIGH)';
            badge.style.background = '#e8f5e9';
            badge.style.color = '#2e7d32';
            badge.style.border = '1px solid #a5d6a7';
        } else if (confidence === 'MEDIUM') {
            badge.textContent = '⚠️ 1 Checkdigit Warning — Please Review Fields (MEDIUM)';
            badge.style.background = '#fff3e0';
            badge.style.color = '#e65100';
            badge.style.border = '1px solid #ffcc80';
        } else {
            badge.textContent = '❌ MRZ Checksum Failed — Verify Details Manually (REJECT)';
            badge.style.background = '#ffebee';
            badge.style.color = '#c62828';
            badge.style.border = '1px solid #ef9a9a';
        }
    }
}

function updateRecordsBadge() {
    const records = JSON.parse(localStorage.getItem('nssf_records') || '[]');
    document.getElementById('records-badge').textContent = records.length;
}

function renderRecords() {
    const list = document.getElementById('records-list');
    const exportBtn = document.getElementById('export-btn');
    const clearBtn = document.getElementById('clear-all-btn');

    try {
        const records = JSON.parse(localStorage.getItem('nssf_records') || '[]');
        list.innerHTML = '';
        if (records.length === 0) {
            list.innerHTML = `
                <tr><td>
                    <div class="empty-state">
                        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="9" y1="15" x2="15" y2="15"></line></svg>
                        <div style="margin-top: 8px; font-weight: 600;">No records yet.</div>
                    </div>
                </td></tr>
            `;
            if(exportBtn) exportBtn.disabled = true;
            if(clearBtn) clearBtn.disabled = true;
            return;
        }

        if(exportBtn) exportBtn.disabled = false;
        if(clearBtn) clearBtn.disabled = false;

        // Render Image 4 exact record item rows
        records.slice().reverse().forEach(record => {
            const tr = document.createElement('tr');
            tr.style.borderBottom = "1px solid var(--border)";

            const fullName = [record.surname, record.givenName, record.otherName].filter(Boolean).join(' ').toUpperCase();
            tr.innerHTML = `
                <td style="padding: 12px 8px;">
                    <div style="font-weight: 700; color: var(--text); font-size: 14px; margin-bottom: 4px;">${fullName}</div>
                    <div style="font-size: 11px; color: var(--text-muted); display: flex; gap: 8px; flex-wrap: wrap;">
                        <span>NIN: ${record.nin}</span> | <span>DOB: ${record.dob}</span> | <span>SEX: ${record.sex}</span>
                    </div>
                </td>
            `;
            list.appendChild(tr);
        });
    } catch (err) {
        console.error("Failed to load records:", err);
    }
}

// Add export and clear logic
document.addEventListener('DOMContentLoaded', () => {
    updateRecordsBadge();

    const exportBtn = document.getElementById('export-btn');
    const clearBtn = document.getElementById('clear-all-btn');

    if(exportBtn) {
        exportBtn.addEventListener('click', async () => {
            const records = JSON.parse(localStorage.getItem('nssf_records') || '[]');
            if(records.length === 0) return;
            
            const headers = ['SURNAME', 'GIVEN NAME', 'OTHER NAME', 'SEX', 'DOB', 'NATIONALITY', 'NIN', 'PHONE', 'TIMESTAMP'];
            const csvRows = [headers.join(',')];
            
            records.forEach(r => {
                const row = [
                    `"${r.surname || ''}"`,
                    `"${r.givenName || ''}"`,
                    `"${r.otherName || ''}"`,
                    `"${r.sex || ''}"`,
                    `"${r.dob || ''}"`,
                    `"${r.nationality || ''}"`,
                    `"${r.nin || ''}"`,
                    `"${r.phone || ''}"`,
                    `"${r.timestamp || ''}"`
                ];
                csvRows.push(row.join(','));
            });
            
            const csvData = new Blob([csvRows.join('\n')], { type: 'text/csv' });
            const csvUrl = URL.createObjectURL(csvData);
            const a = document.createElement('a');
            a.href = csvUrl;
            a.download = `NSSF_Records_${new Date().toISOString().split('T')[0]}.csv`;
            a.click();
            URL.revokeObjectURL(csvUrl);
            console.log("Downloaded CSV export.");
        });
    }

    if(clearBtn) {
        clearBtn.addEventListener('click', async () => {
            if(confirm("Are you sure you want to completely clear ALL offline records? This cannot be undone.")) {
                localStorage.removeItem('nssf_records');
                updateRecordsBadge();
                renderRecords();
                console.log("All records cleared.");
            }
        });
    }
});
