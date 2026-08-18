/**
 * ug-id-parser.js - Client-Side JavaScript Ugandan ID Card Parser Engine
 * 100% Offline Parsing for PWA & Mobile Web Browsers
 */

const UgIdParser = (function() {
    const NIN_REGEX = /^[A-Z]{2}[0-9]{2}[A-Z0-9]{10}$/i;
    const OLD_NIN_REGEX = /^[A-Z]{2}[0-9]{9}[A-Z]{3}$/i;
    const NEW_NIN_REGEX = /^[A-Z]{2}[0-9]{10}[A-Z]{2}$/i;

    const DIGIT_TO_LETTER = {'0': 'O', '1': 'I', '5': 'S', '8': 'B', '6': 'G', '4': 'A', '2': 'Z', '3': 'J'};
    const LETTER_TO_DIGIT = {
        'O': '0', 'I': '1', 'S': '5', 'B': '8', 'G': '6', 'A': '4', 'Z': '2',
        'D': '0', 'E': '0', 'Q': '0', 'R': '8', 'T': '7', 'Y': '7', 'U': '0',
        'P': '9', 'H': '8', 'L': '1'
    };

    function cleanMrzNameToken(t) {
        if (!t) return "";
        let s = t.replace(/0/g, 'O').replace(/1/g, 'I').replace(/5/g, 'S').replace(/8/g, 'B');
        return s.replace(/[^A-Z]/g, '');
    }

    function tryNormalizeOldFormat(chars) {
        let c = [...chars];
        for (let i = 2; i < 11 && i < c.length; i++) {
            if (LETTER_TO_DIGIT[c[i]]) c[i] = LETTER_TO_DIGIT[c[i]];
        }
        for (let i = 11; i < 14 && i < c.length; i++) {
            if (DIGIT_TO_LETTER[c[i]]) c[i] = DIGIT_TO_LETTER[c[i]];
        }
        return c.join('');
    }

    function tryNormalizeNewFormat(chars) {
        let c = [...chars];
        for (let i = 2; i < 12 && i < c.length; i++) {
            if (LETTER_TO_DIGIT[c[i]]) c[i] = LETTER_TO_DIGIT[c[i]];
        }
        for (let i = 12; i < 14 && i < c.length; i++) {
            if (DIGIT_TO_LETTER[c[i]]) c[i] = DIGIT_TO_LETTER[c[i]];
        }
        return c.join('');
    }

    function normalizeNinCandidate(candidate) {
        if (!candidate) return "";
        let v = candidate.toUpperCase().replace(/€/g, 'C').replace(/[^A-Z0-9]/g, '');

        if (v.length === 15 && /^[CAP][MF][O0I1L][A-Z0-9]{12}$/i.test(v)) {
            v = v.substring(0, 2) + v.substring(3);
        }

        const embedded = v.match(/[CAP1G0OI4L][MFN13PR0-9BH][A-Z0-9]{12}/i);
        if (embedded && embedded[0] !== v) {
            return normalizeNinCandidate(embedded[0]);
        }

        if (v.length !== 14) {
            const match = v.match(/([CAP1G0OI4L][MFN13PR0-9BH])([A-Z0-9]{12})/i);
            if (match) {
                v = match[0];
            } else {
                return "";
            }
        }

        let chars = v.split('');

        // Prefix repair (CM / CF)
        for (let i = 0; i < 2; i++) {
            if (DIGIT_TO_LETTER[chars[i]]) chars[i] = DIGIT_TO_LETTER[chars[i]];
        }
        if (['I', '1', 'O', '0'].includes(chars[0])) chars[0] = 'C';
        else if (!['A', 'P'].includes(chars[0])) chars[0] = 'C';

        if (['N', 'H', 'K', 'R', 'P'].includes(chars[1])) chars[1] = 'M';

        if (v.length >= 14 && /[A-Z]/i.test(v[11]) && !['O', 'I', 'S', 'B', 'G', 'A', 'Z'].includes(v[11])) {
            let oldCand = tryNormalizeOldFormat(chars);
            if (OLD_NIN_REGEX.test(oldCand)) return oldCand;
        }

        let newCand = tryNormalizeNewFormat(chars);
        if (NEW_NIN_REGEX.test(newCand)) return newCand;

        let oldCand = tryNormalizeOldFormat(chars);
        if (OLD_NIN_REGEX.test(oldCand)) return oldCand;
        if (NIN_REGEX.test(newCand)) return newCand;
        if (NIN_REGEX.test(oldCand)) return oldCand;

        return newCand.length === 14 ? newCand : oldCand;
    }

    function decodeBase64Utf8(str) {
        try {
            return decodeURIComponent(escape(atob(str.trim())));
        } catch (e) {
            return str.trim().toUpperCase();
        }
    }

    function parseBarcodePayload(raw) {
        if (!raw || (!raw.includes(';') && !raw.includes('[FNG]'))) return null;
        const parts = raw.split('[FNG]')[0].split(';');
        if (parts.length < 8) return null;

        const surname = decodeBase64Utf8(parts[0]);
        const givenName = decodeBase64Utf8(parts[1]);
        const otherName = decodeBase64Utf8(parts[2]);
        const dobRaw = parts[3] ? parts[3].trim() : '';
        const ninRaw = parts[6] ? parts[6].trim() : '';
        const cardNo = parts[7] ? parts[7].trim() : '';

        let dob = "";
        if (dobRaw.length === 8) {
            dob = `${dobRaw.substring(4, 8)}-${dobRaw.substring(2, 4)}-${dobRaw.substring(0, 2)}`;
        }

        const nin = normalizeNinCandidate(ninRaw);
        let sex = "Male";
        if (nin.startsWith("CF") || nin.startsWith("AF") || nin.startsWith("PF")) {
            sex = "Female";
        }

        return {
            surname: surname,
            given_name: givenName,
            other_name: otherName,
            date_of_birth: dob,
            sex: sex,
            nin: nin,
            card_number: cardNo,
            source: 'Barcode (PDF417)'
        };
    }

    function parseMrzTextLines(lines) {
        if (!Array.isArray(lines) || lines.length === 0) return null;

        const candidates = lines
            .map(l => l.trim().replace(/\s+/g, '').toUpperCase().replace(/€/g, 'C'))
            .filter(l => l.includes('UGA') || l.includes('<') || l.includes('CM0') || l.includes('CF0') || l.includes('IDUGA'));

        if (candidates.length === 0) return null;

        let line1 = null, line2 = null, line3 = null;
        for (let l of candidates) {
            if (l.includes('IDUGA') || (l.length >= 25 && (l.includes('CM') || l.includes('CF') || l.startsWith('UGA')))) {
                line1 = l;
            } else if (/\d{6}[MF\d]\d{6}UGA/.test(l) || (l.length >= 20 && l.includes('UGA'))) {
                line2 = l;
            } else if (l.includes('<<') || (l.length >= 15 && l.includes('<'))) {
                line3 = l;
            }
        }

        if (!line1 || !line3) {
            if (candidates.length >= 3) {
                line1 = candidates[0]; line2 = candidates[1]; line3 = candidates[2];
            } else if (candidates.length >= 2) {
                line1 = candidates[0]; line3 = candidates[1];
            } else {
                return null;
            }
        }

        // Line 1: Card Number & NIN
        let cardNumber = "", nin = "";
        const m1Exact = line1.match(/IDUGA(?<card_no>\d{9})\d(?<nin>[A-Z0-9<]{14,15})/);
        if (m1Exact && m1Exact.groups) {
            cardNumber = m1Exact.groups.card_no;
            nin = normalizeNinCandidate(m1Exact.groups.nin.replace(/</g, ''));
        } else {
            const m1Fallback = line1.match(/IDUGA(?<card_no>\d{10})(?<nin>[A-Z0-9<]{14,15})/);
            if (m1Fallback && m1Fallback.groups) {
                cardNumber = m1Fallback.groups.card_no;
                nin = normalizeNinCandidate(m1Fallback.groups.nin.replace(/</g, ''));
            } else {
                const cardMatch = line1.match(/\d{9,10}/);
                cardNumber = cardMatch ? cardMatch[0] : "";
                const ninMatch = line1.match(/[A-Z]{2}\d{8,9}[A-Z0-9]{3,4}/);
                nin = ninMatch ? normalizeNinCandidate(ninMatch[0]) : "";
            }
        }

        // Line 2: DOB & Sex
        let dob = "", sex = "Male";
        if (line2) {
            const m2 = line2.match(/(?<dob>\d{6})(?<cd2>\d)(?<sex_char>[MF<])(?<exp>\d{6})(?<cd3>\d)UGA/);
            if (m2 && m2.groups) {
                const dobStr = m2.groups.dob;
                const yy = parseInt(dobStr.substring(0, 2), 10);
                const year = yy <= 30 ? 2000 + yy : 1900 + yy;
                dob = `${year}-${dobStr.substring(2, 4)}-${dobStr.substring(4, 6)}`;
                if (m2.groups.sex_char === 'F') sex = 'Female';
                else if (m2.groups.sex_char === 'M') sex = 'Male';
            }
        }

        if (nin) {
            if (nin.startsWith("CF") || nin.startsWith("AF") || nin.startsWith("PF")) {
                sex = "Female";
            }
        }

        // Line 3: Surname, Given Name, Other Name
        let surname = "", givenName = "", otherName = "";
        if (line3) {
            const clean3 = line3.replace(/<+$/, '').replace(/\s+/g, '');
            if (clean3.includes('<<')) {
                const parts = clean3.split('<<');
                if (parts.length >= 1) surname = cleanMrzNameToken(parts[0].replace(/</g, ' '));
                if (parts.length >= 2) {
                    const givenParts = parts[1].split('<');
                    givenName = cleanMrzNameToken(givenParts[0]);
                    if (givenParts.length > 1) {
                        otherName = givenParts.slice(1).map(p => cleanMrzNameToken(p)).filter(Boolean).join(' ');
                    }
                }
            } else {
                const parts = clean3.split('<').map(p => cleanMrzNameToken(p)).filter(Boolean);
                if (parts.length >= 1) surname = parts[0];
                if (parts.length >= 2) givenName = parts[1];
                if (parts.length >= 3) otherName = parts.slice(2).join(' ');
            }
        }

        return {
            surname: surname,
            given_name: givenName,
            other_name: otherName,
            date_of_birth: dob,
            sex: sex,
            nin: nin,
            card_number: cardNumber,
            source: 'MRZ OCR'
        };
    }

    return {
        normalizeNinCandidate: normalizeNinCandidate,
        parseBarcodePayload: parseBarcodePayload,
        parseMrzTextLines: parseMrzTextLines
    };
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = UgIdParser;
}
