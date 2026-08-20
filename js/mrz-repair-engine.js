/**
 * mrz-repair-engine.js
 * PWA Browser Bounded Check-Digit Repair Engine
 * 
 * When UgandaIdParser returns ValidationConfidence.MEDIUM (1 check digit failure),
 * this engine generates candidate repairs by substituting visually confusable
 * characters, then presents them to the user for confirmation via a modal dialog.
 * 
 * NEVER auto-applies repairs. User must explicitly confirm.
 * 
 * @version 1.0.0
 */

import { UgandaIdParser, ValidationConfidence } from './uganda-id-parser.js';

/**
 * OCR confusion table for single-character substitutions.
 */
const OCR_CONFUSIONS = {
    '0': ['O'], 'O': ['0'],
    '1': ['I', 'L'], 'I': ['1', 'L'], 'L': ['1', 'I'],
    '5': ['S'], 'S': ['5'],
    '8': ['B'], 'B': ['8'],
    '6': ['G'], 'G': ['6'],
    '4': ['A'], 'A': ['4'],
    '2': ['Z'], 'Z': ['2'],
    '7': ['T'], 'T': ['7'],
    '9': ['P'], 'P': ['9'],
    '3': ['E'], 'E': ['3']
};

/**
 * Generate repair candidates for a MEDIUM-confidence MRZ parse.
 * 
 * @param {string} line1 — 30-char MRZ line 1
 * @param {string} line2 — 30-char MRZ line 2
 * @returns {Array<Object>} Candidates that achieve HIGH confidence
 */
export function generateRepairCandidates(line1, line2) {
    const candidates = [];

    const fields = [
        { name: 'Card Number', value: line1.slice(5, 14), cd: 'cd1', line: '1', offset: 5 },
        { name: 'Date of Birth', value: line2.slice(0, 6), cd: 'cd2', line: '2', offset: 0 },
        { name: 'Expiry Date', value: line2.slice(8, 14), cd: 'cd3', line: '2', offset: 8 }
    ];

    for (const field of fields) {
        const chars = field.value.split('');
        for (let i = 0; i < chars.length; i++) {
            const original = chars[i];
            const alternatives = OCR_CONFUSIONS[original] || [];

            for (const alt of alternatives) {
                chars[i] = alt;
                const repairedValue = chars.join('');

                // Reconstruct lines
                let testLine1 = line1;
                let testLine2 = line2;

                if (field.line === '1') {
                    testLine1 = line1.slice(0, 5) + repairedValue + line1.slice(14);
                } else if (field.cd === 'cd2') {
                    testLine2 = repairedValue + line2.slice(6);
                } else if (field.cd === 'cd3') {
                    testLine2 = line2.slice(0, 8) + repairedValue + line2.slice(14);
                }

                // Re-validate
                const result = UgandaIdParser.validateCheckDigits(testLine1, testLine2);
                if (result.confidence === ValidationConfidence.HIGH) {
                    candidates.push({
                        fieldName: field.name,
                        position: i + 1,
                        originalChar: original,
                        replacementChar: alt,
                        repairedValue,
                        repairedLine1: testLine1,
                        repairedLine2: testLine2,
                        description: `Changed '${original}' to '${alt}' at position ${i + 1} in ${field.name}`
                    });
                }
            }
            chars[i] = original; // restore
        }
    }

    return candidates;
}

/**
 * Show repair confirmation modal in the browser.
 * 
 * @param {Object} originalRecord
 * @param {Array<Object>} candidates
 * @param {Function} onConfirm — called with repaired CardRecord
 * @param {Function} onCancel — called when user rejects all repairs
 */
export function showRepairDialog(originalRecord, candidates, onConfirm, onCancel) {
    const existing = document.getElementById('mrz-repair-modal');
    if (existing) existing.remove();

    const modal = document.createElement('div');
    modal.id = 'mrz-repair-modal';
    modal.style.cssText = `
        position: fixed; top: 0; left: 0; width: 100%; height: 100%;
        background: rgba(0,0,0,0.6); z-index: 10000;
        display: flex; align-items: center; justify-content: center;
        font-family: sans-serif;
    `;

    const panel = document.createElement('div');
    panel.style.cssText = `
        background: white; border-radius: 12px; padding: 24px;
        max-width: 90%; width: 400px; max-height: 80vh; overflow-y: auto;
        box-shadow: 0 8px 32px rgba(0,0,0,0.3);
    `;

    const title = document.createElement('h3');
    title.textContent = '⚠️ MRZ Check Digit Warning';
    title.style.marginTop = '0';
    panel.appendChild(title);

    const subtitle = document.createElement('p');
    subtitle.textContent = 'One field may have an OCR error. Please confirm the correct value:';
    subtitle.style.color = '#666';
    panel.appendChild(subtitle);

    const originalBox = document.createElement('div');
    originalBox.style.cssText = 'background: #f5f5f5; padding: 12px; border-radius: 8px; margin: 12px 0; font-size: 13px;';
    originalBox.innerHTML = `<strong>Original:</strong><br>NIN: ${originalRecord.nin}<br>Card: ${originalRecord.cardNumber}<br>DOB: ${originalRecord.dateOfBirth}`;
    panel.appendChild(originalBox);

    const maxShow = Math.min(3, candidates.length);
    for (let i = 0; i < maxShow; i++) {
        const cand = candidates[i];
        const btn = document.createElement('button');
        btn.textContent = `${i + 1}. ${cand.description}`;
        btn.style.cssText = `
            display: block; width: 100%; padding: 14px; margin: 8px 0;
            border: 2px solid #4CAF50; border-radius: 8px; background: #E8F5E9;
            color: #2E7D32; font-size: 14px; font-weight: 600; cursor: pointer;
        `;
        btn.onmouseenter = () => { btn.style.background = '#C8E6C9'; };
        btn.onmouseleave = () => { btn.style.background = '#E8F5E9'; };
        btn.onclick = () => {
            const repaired = UgandaIdParser.parseMrzLines([
                cand.repairedLine1,
                cand.repairedLine2,
                '<<'
            ]);
            if (repaired) {
                repaired.validationConfidence = ValidationConfidence.HIGH;
                repaired.validationFailures = 0;
                repaired.phoneNumber = originalRecord.phoneNumber;
            }
            modal.remove();
            if (onConfirm) onConfirm(repaired || originalRecord);
        };
        panel.appendChild(btn);
    }

    const manualBtn = document.createElement('button');
    manualBtn.textContent = 'Enter Manually';
    manualBtn.style.cssText = `
        display: block; width: 100%; padding: 14px; margin-top: 12px;
        border: 2px solid #F44336; border-radius: 8px; background: #FFEBEE;
        color: #C62828; font-size: 14px; font-weight: 600; cursor: pointer;
    `;
    manualBtn.onclick = () => {
        modal.remove();
        if (onCancel) onCancel();
    };
    panel.appendChild(manualBtn);

    modal.appendChild(panel);
    document.body.appendChild(modal);
}

/**
 * Convenience: attempt repair and auto-show dialog.
 * 
 * @param {Object} record — MEDIUM confidence record
 * @param {string} line1 — raw line 1
 * @param {string} line2 — raw line 2
 * @param {Function} onConfirm
 * @param {Function} onCancel
 */
export function attemptRepair(record, line1, line2, onConfirm, onCancel) {
    if (!record || record.validationConfidence !== ValidationConfidence.MEDIUM) {
        if (onCancel) onCancel();
        return;
    }

    const candidates = generateRepairCandidates(line1, line2);
    if (candidates.length === 0) {
        alert('⚠️ Could not auto-suggest a repair. Please verify manually.');
        if (onCancel) onCancel();
        return;
    }

    showRepairDialog(record, candidates, onConfirm, onCancel);
}
