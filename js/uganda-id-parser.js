/**
 * uganda-id-parser.js
 * Uganda National ID MRZ Parser — Ugandan NID-Specific Check Digit Variant
 *
 * ⚠️ CRITICAL: Ugandan National ID cards use DIRECT Modulo-10 check digits
 * (sum % 10), NOT the standard ICAO 9303 complement formula
 * (10 - (sum % 10)) % 10 used by passports and most other TD1 documents.
 *
 * Confirmed field layout across test cards (Samuel, Mellisa, Timothy, Elvis):
 *
 * Line 1 (30 chars): ID + UGA + {CARD_NUMBER:9} + {CD1:1} + {NIN:15, padded with <}
 * Line 2 (30 chars): {DOB:6} + {CD2:1} + {SEX:1} + {EXPIRY:6} + {CD3:1} + UGA + {FILLER:11} + {CD_COMPOSITE:1}
 * Line 3 (30 chars): {SURNAME}<<{GIVEN_NAME}<{OTHER_NAME}<<<<<<<<<<<<<<<<<<<<<<<<<<
 *
 * @version 2.1.0
 * @module UgandaIdParser
 */

// ─────────────────────────────────────────────────────────────────────────────
// Enums
// ─────────────────────────────────────────────────────────────────────────────

export const ValidationConfidence = Object.freeze({
    HIGH:   'HIGH',    // All 4 check digits pass
    MEDIUM: 'MEDIUM',  // 1 check digit fails — accept but flag
    REJECT: 'REJECT'   // 2+ check digits fail
});

export const CaptureSource = Object.freeze({
    MRZ: 'MRZ',
    PDF417_BARCODE: 'PDF417_BARCODE',
    MANUAL: 'MANUAL'
});

// ─────────────────────────────────────────────────────────────────────────────
// Data Models
// ─────────────────────────────────────────────────────────────────────────────

export class ValidationResult {
    constructor(failureCount, confidence) {
        this.failureCount = failureCount;
        this.confidence = confidence;
    }
}

export class CardRecord {
    constructor(
        surname = '',
        givenName = '',
        otherName = '',
        sex = 'Male',
        dateOfBirth = '',
        nin = '',
        cardNumber = '',
        phoneNumber = '',
        source = 'Tesseract.js MRZ OCR'
    ) {
        this.surname = surname;
        this.givenName = givenName;
        this.otherName = otherName;
        this.sex = sex;
        this.dateOfBirth = dateOfBirth;
        this.nin = nin;
        this.cardNumber = cardNumber;
        this.phoneNumber = phoneNumber;
        this.source = source;

        // MRZ validation metadata
        this.validationConfidence = ValidationConfidence.HIGH;
        this.validationFailures = 0;
        this.expiryDate = '';
        this.repairCandidates = null;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Parser
// ─────────────────────────────────────────────────────────────────────────────

export class UgandaIdParser {

    // NIN format validators
    static OLD_NIN_REGEX = /^[A-Z]{2}[0-9]{9}[A-Z]{3}$/i;
    static NEW_NIN_REGEX = /^[A-Z]{2}[0-9]{10}[A-Z]{2}$/i;

    // MRZ line validators (exact 30-char TD1)
    static LINE1_REGEX = /^IDUGA(\d{9})(\d)([A-Z0-9<]{15})$/;
    static LINE2_REGEX = /^(\d{6})(\d)([MF<])(\d{6})(\d)UGA([A-Z0-9<]{11})(\d)$/;
    static LINE3_REGEX = /^([A-Z<]+)$/;

    // OCR confusion correction maps
    static DIGIT_TO_LETTER = new Map([
        ['0', 'O'], ['1', 'I'], ['5', 'S'],
        ['8', 'B'], ['6', 'G'], ['4', 'A'], ['2', 'Z']
    ]);

    static LETTER_TO_DIGIT = new Map([
        ['O', '0'], ['I', '1'], ['S', '5'],
        ['B', '8'], ['G', '6'], ['A', '4'],
        ['Z', '2'], ['D', '0'], ['E', '0'],
        ['Q', '0'], ['R', '8'], ['T', '7'],
        ['Y', '7'], ['U', '0'], ['P', '9'],
        ['H', '8'], ['L', '1']
    ]);

    // ─────────────────────────────────────────────────────────────────────────
    // UGANDAN NID Check Digit Calculator (DIRECT Modulo-10)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Calculate Ugandan National ID DIRECT Modulo-10 check digit.
     *
     * ⚠️ NOTE: This is NOT the standard ICAO 9303 formula.
     * Standard ICAO 9303: (10 - (sum % 10)) % 10
     * Ugandan NIDs use:    sum % 10
     *
     * Weights: 7, 3, 1 repeating.
     * Mapping: 0-9 → 0-9, A-Z → 10-35, < → 0
     *
     * @param {string} data
     * @returns {number} 0-9
     */
    static calculateCheckDigit(data) {
        const weights = [7, 3, 1];
        let sum = 0;
        for (let i = 0; i < data.length; i++) {
            const c = data[i];
            let value;
            if (c === '<') {
                value = 0;
            } else if (c >= '0' && c <= '9') {
                value = c.charCodeAt(0) - '0'.charCodeAt(0);
            } else if (c >= 'A' && c <= 'Z') {
                value = 10 + (c.charCodeAt(0) - 'A'.charCodeAt(0));
            } else {
                value = 0;
            }
            sum += value * weights[i % 3];
        }
        // ⚠️ UGANDAN NID SPECIFIC: Direct modulo, NOT complement
        return sum % 10;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Check Digit Validation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validate all check digits with graceful degradation.
     *
     * @param {string} line1 — 30-char MRZ line 1
     * @param {string} line2 — 30-char MRZ line 2
     * @returns {ValidationResult}
     */
    static validateCheckDigits(line1, line2) {
        if (!line1 || !line2 || line1.length !== 30 || line2.length !== 30) {
            return new ValidationResult(4, ValidationConfidence.REJECT);
        }

        let failures = 0;

        // CD1: Card number — line1 positions 6-14 (0-indexed 5-14), 9 chars
        const cardNumber = line1.slice(5, 14);
        const expectedCd1 = UgandaIdParser.calculateCheckDigit(cardNumber);
        const actualCd1 = parseInt(line1[14], 10);
        if (expectedCd1 !== actualCd1) failures++;

        // CD2: Date of birth — line2 positions 1-6 (0-indexed 0-5), 6 chars
        const dobRaw = line2.slice(0, 6);
        const expectedCd2 = UgandaIdParser.calculateCheckDigit(dobRaw);
        const actualCd2 = parseInt(line2[6], 10);
        if (expectedCd2 !== actualCd2) failures++;

        // CD3: Date of expiry — line2 positions 9-14 (0-indexed 8-13), 6 chars
        const expiryRaw = line2.slice(8, 14);
        const expectedCd3 = UgandaIdParser.calculateCheckDigit(expiryRaw);
        const actualCd3 = parseInt(line2[14], 10);
        if (expectedCd3 !== actualCd3) failures++;

        // CD_COMPOSITE: line1[5:30] + line2[0:29] = 54 chars
        const compositeData = line1.slice(5, 30) + line2.slice(0, 29);
        const expectedCdComposite = UgandaIdParser.calculateCheckDigit(compositeData);
        const actualCdComposite = parseInt(line2[29], 10);
        if (expectedCdComposite !== actualCdComposite) failures++;

        let confidence;
        switch (failures) {
            case 0:
                confidence = ValidationConfidence.HIGH;
                break;
            case 1:
                confidence = ValidationConfidence.MEDIUM;
                break;
            default:
                confidence = ValidationConfidence.REJECT;
                break;
        }

        return new ValidationResult(failures, confidence);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NIN Normalizer
    // ─────────────────────────────────────────────────────────────────────────

    static cleanMrzNameToken(token) {
        if (!token) return '';
        let s = token
            .replace(/0/g, 'O')
            .replace(/1/g, 'I')
            .replace(/5/g, 'S')
            .replace(/8/g, 'B');
        return s.replace(/[^A-Z]/gi, '').toUpperCase();
    }

    static UGANDA_NIN_REGEX = /^[A-Z]{2}[0-9]{7}[A-Z0-9]{5}$/i;

    static tryNormalizeFormat(chars) {
        const c = [...chars];
        // Positions 2..8 (7 chars): Must be DIGITS
        for (let i = 2; i <= 8 && i < c.length; i++) {
            if (UgandaIdParser.LETTER_TO_DIGIT.has(c[i])) {
                c[i] = UgandaIdParser.LETTER_TO_DIGIT.get(c[i]);
            }
        }
        // Positions 9..13 (5 chars): Alphanumeric serial - PRESERVE AS-IS!
        return c.join('');
    }

    static normalizeNinCandidate(candidate) {
        if (!candidate) return '';
        let v = candidate.toUpperCase().replace(/€/g, 'C').replace(/[^A-Z0-9]/g, '');

        if (v.length === 15 && /^[CAP][MF][O0I1L][A-Z0-9]{12}$/.test(v)) {
            v = v.slice(0, 2) + v.slice(3);
        }

        if (v.length !== 14) {
            const match = v.match(/([CAP1G0OI4L][MFN13PR0-9BH])([A-Z0-9]{12})/);
            if (match) {
                v = match[0];
            } else {
                return '';
            }
        }

        const chars = v.split('');
        for (let i = 0; i <= 1 && i < chars.length; i++) {
            if (UgandaIdParser.DIGIT_TO_LETTER.has(chars[i])) {
                chars[i] = UgandaIdParser.DIGIT_TO_LETTER.get(chars[i]);
            }
        }
        if (chars.length > 0 && ['I', '1', 'O', '0'].includes(chars[0])) {
            chars[0] = 'C';
        }

        const normalized = UgandaIdParser.tryNormalizeFormat(chars);
        if (UgandaIdParser.UGANDA_NIN_REGEX.test(normalized)) return normalized;

        return normalized.length === 14 ? normalized : v;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Date Formatter
    // ─────────────────────────────────────────────────────────────────────────

    static formatDate(yymmdd) {
        if (!yymmdd || yymmdd.length !== 6 || !/^\d{6}$/.test(yymmdd)) {
            return '';
        }
        const yy = parseInt(yymmdd.slice(0, 2), 10);
        const year = (yy <= 30) ? 2000 + yy : 1900 + yy;
        return `${year}-${yymmdd.slice(2, 4)}-${yymmdd.slice(4, 6)}`;
    }

    static padTo30(s) {
        if (s.length >= 30) return s.slice(0, 30);
        return s.padEnd(30, '<');
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main Parser
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse MRZ lines from OCR text output.
     *
     * @param {string[]} lines — Raw OCR text lines
     * @returns {CardRecord|null} Parsed record, or null if MRZ cannot be decoded
     */
    static parseMrzLines(lines) {
        if (!lines || lines.length === 0) return null;

        // Step 1: Extract candidate MRZ lines
        const candidates = [];
        for (const l of lines) {
            const clean = l.trim().replace(/\s+/g, '').toUpperCase().replace(/€/g, 'C');
            if (clean.length >= 25 && (/IDUGA/.test(clean) || /UGA/.test(clean) || /<</.test(clean))) {
                candidates.push(clean);
            }
        }

        if (candidates.length === 0) return null;

        // Step 2: Identify line 1, line 2, line 3 by signature
        let line1 = null, line2 = null, line3 = null;
        for (const l of candidates) {
            if (l.startsWith('IDUGA') && l.length >= 30 && /\d{9}/.test(l)) {
                line1 = l.length >= 30 ? l.slice(0, 30) : UgandaIdParser.padTo30(l);
            } else if (/^\d{6}/.test(l) && /UGA/.test(l) && l.length >= 30) {
                line2 = l.slice(0, 30);
            } else if (/<</.test(l) && l.length >= 10) {
                line3 = l.length >= 30 ? l.slice(0, 30) : UgandaIdParser.padTo30(l);
            }
        }

        // Fallback: assign by order if signatures fail
        if (line1 == null || line2 == null || line3 == null) {
            const longLines = candidates.filter(c => c.length >= 25);
            if (longLines.length >= 3) {
                line1 = UgandaIdParser.padTo30(longLines[0]);
                line2 = UgandaIdParser.padTo30(longLines[1]);
                line3 = UgandaIdParser.padTo30(longLines[2]);
            } else if (longLines.length >= 2) {
                line1 = UgandaIdParser.padTo30(longLines[0]);
                if (line1.includes('IDUGA')) {
                    line2 = UgandaIdParser.padTo30(longLines[1]);
                } else {
                    line3 = UgandaIdParser.padTo30(longLines[1]);
                }
            }
        }

        if (line1 == null || line3 == null) return null;
        if (line1.length < 30) line1 = UgandaIdParser.padTo30(line1);
        if (line2 != null && line2.length < 30) line2 = UgandaIdParser.padTo30(line2);
        if (line3.length < 30) line3 = UgandaIdParser.padTo30(line3);

        // Step 3: Parse Line 1
        let cardNumber = '';
        let nin = '';
        const m1 = UgandaIdParser.LINE1_REGEX.exec(line1);
        if (m1) {
            cardNumber = m1[1];
            const ninField = m1[3].replace(/</g, '');
            nin = UgandaIdParser.normalizeNinCandidate(ninField);
        } else {
            const cardMatch = line1.match(/IDUGA(\d{9})/);
            if (cardMatch) cardNumber = cardMatch[1];
            const ninMatch = line1.match(/[A-Z]{2}\d{8,10}[A-Z]{2,3}/);
            if (ninMatch) nin = UgandaIdParser.normalizeNinCandidate(ninMatch[0]);
        }

        // Step 4: Parse Line 2
        let dob = '';
        let sex = 'Male';
        let expiryDate = '';
        let validation = new ValidationResult(4, ValidationConfidence.REJECT);

        if (line2 != null) {
            const m2 = UgandaIdParser.LINE2_REGEX.exec(line2);
            if (m2) {
                dob = UgandaIdParser.formatDate(m2[1]);

                const sexChar = m2[3];
                if (sexChar === 'F') sex = 'Female';
                else if (sexChar === 'M') sex = 'Male';

                expiryDate = UgandaIdParser.formatDate(m2[4]);
                validation = UgandaIdParser.validateCheckDigits(line1, line2);
            }
        }

        // Override sex based on NIN prefix (Ugandan convention)
        if (/^(CF|AF|PF)/i.test(nin)) {
            sex = 'Female';
        }

        // Step 5: Parse Line 3 — Names
        let surname = '';
        let givenName = '';
        let otherName = '';

        const clean3 = line3.replace(/<+$/g, '').replace(/\s+/g, '');
        if (clean3.includes('<<')) {
            const parts = clean3.split('<<');
            if (parts.length >= 1) {
                surname = UgandaIdParser.cleanMrzNameToken(parts[0].replace(/</g, ' '));
            }
            if (parts.length >= 2) {
                const givenParts = parts[1].split('<');
                givenName = UgandaIdParser.cleanMrzNameToken(givenParts[0]);
                if (givenParts.length > 1) {
                    const sb = [];
                    for (let i = 1; i < givenParts.length; i++) {
                        const cleanTok = UgandaIdParser.cleanMrzNameToken(givenParts[i]);
                        if (cleanTok) sb.push(cleanTok);
                    }
                    otherName = sb.join(' ');
                }
            }
        } else {
            const rawParts = clean3.split('<');
            const parts = [];
            for (const p of rawParts) {
                const c = UgandaIdParser.cleanMrzNameToken(p);
                if (c) parts.push(c);
            }
            if (parts.length >= 1) surname = parts[0];
            if (parts.length >= 2) givenName = parts[1];
            if (parts.length >= 3) {
                otherName = parts.slice(2).join(' ');
            }
        }

        const record = new CardRecord(
            surname, givenName, otherName, sex, dob, nin, cardNumber,
            '', 'Tesseract.js MRZ OCR'
        );
        record.validationConfidence = validation.confidence;
        record.validationFailures = validation.failureCount;
        record.expiryDate = expiryDate;

        return record;
    }
}

export default UgandaIdParser;
