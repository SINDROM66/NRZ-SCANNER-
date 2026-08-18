package com.nssf.datacapture

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

data class CardRecord(
    var surname: String = "",
    var givenName: String = "",
    var otherName: String = "",
    var sex: String = "Male",
    var dateOfBirth: String = "",
    var nin: String = "",
    var cardNumber: String = "",
    var phoneNumber: String = "",
    var source: String = "Offline Native Scanner"
)

object UgandaIdParser {

    private val OLD_NIN_REGEX = Pattern.compile("^[A-Z]{2}[0-9]{9}[A-Z]{3}$", Pattern.CASE_INSENSITIVE)
    private val NEW_NIN_REGEX = Pattern.compile("^[A-Z]{2}[0-9]{10}[A-Z]{2}$", Pattern.CASE_INSENSITIVE)

    private val DIGIT_TO_LETTER = mapOf(
        '0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '6' to 'G', '4' to 'A', '2' to 'Z'
    )
    private val LETTER_TO_DIGIT = mapOf(
        'O' to '0', 'I' to '1', 'S' to '5', 'B' to '8', 'G' to '6', 'A' to '4', 'Z' to '2',
        'D' to '0', 'E' to '0', 'Q' to '0', 'R' to '8', 'T' to '7', 'Y' to '7', 'U' to '0',
        'P' to '9', 'H' to '8', 'L' to '1'
    )

    fun cleanMrzNameToken(token: String?): String {
        if (token.isNullOrEmpty()) return ""
        var s = token.replace('0', 'O').replace('1', 'I').replace('5', 'S').replace('8', 'B')
        return s.replace("[^A-Z]".toRegex(), "")
    }

    private fun tryNormalizeOldFormat(chars: CharArray): String {
        val c = chars.copyOf()
        for (i in 2 until minOf(11, c.size)) {
            LETTER_TO_DIGIT[c[i]]?.let { c[i] = it }
        }
        for (i in 11 until minOf(14, c.size)) {
            DIGIT_TO_LETTER[c[i]]?.let { c[i] = it }
        }
        return String(c)
    }

    private fun tryNormalizeNewFormat(chars: CharArray): String {
        val c = chars.copyOf()
        for (i in 2 until minOf(12, c.size)) {
            LETTER_TO_DIGIT[c[i]]?.let { c[i] = it }
        }
        for (i in 12 until minOf(14, c.size)) {
            DIGIT_TO_LETTER[c[i]]?.let { c[i] = it }
        }
        return String(c)
    }

    fun normalizeNinCandidate(candidate: String?): String {
        if (candidate.isNullOrEmpty()) return ""
        var v = candidate.uppercase().replace('€', 'C').replace("[^A-Z0-9]".toRegex(), "")

        if (v.length == 15 && v.matches("^[CAP][MF][O0I1L][A-Z0-9]{12}$".toRegex())) {
            v = v.substring(0, 2) + v.substring(3)
        }

        if (v.length != 14) {
            val matcher = Pattern.compile("([CAP1G0OI4L][MFN13PR0-9BH])([A-Z0-9]{12})").matcher(v)
            if (matcher.find()) {
                v = matcher.group(0)!!
            } else {
                return ""
            }
        }

        val chars = v.toCharArray()
        for (i in 0..1) {
            DIGIT_TO_LETTER[chars[i]]?.let { chars[i] = it }
        }
        if (chars[0] in listOf('I', '1', 'O', '0')) chars[0] = 'C'

        val newCand = tryNormalizeNewFormat(chars)
        if (NEW_NIN_REGEX.matcher(newCand).matches()) return newCand

        val oldCand = tryNormalizeOldFormat(chars)
        if (OLD_NIN_REGEX.matcher(oldCand).matches()) return oldCand

        return if (newCand.length == 14) newCand else oldCand
    }

    fun parseBarcodePayload(rawPayload: String?): CardRecord? {
        if (rawPayload.isNullOrEmpty() || (!rawPayload.contains(";") && !rawPayload.contains("[FNG]"))) {
            return null
        }
        val clean = rawPayload.split("[FNG]")[0]
        val parts = clean.split(";")
        if (parts.size < 8) return null

        val surname = decodeBase64Utf8(parts[0])
        val givenName = decodeBase64Utf8(parts[1])
        val otherName = decodeBase64Utf8(parts[2])
        val dobRaw = if (parts.size > 3) parts[3].trim() else ""
        val ninRaw = if (parts.size > 6) parts[6].trim() else ""
        val cardNo = if (parts.size > 7) parts[7].trim() else ""

        var dob = ""
        if (dobRaw.length == 8) {
            dob = "${dobRaw.substring(4, 8)}-${dobRaw.substring(2, 4)}-${dobRaw.substring(0, 2)}"
        }

        val nin = normalizeNinCandidate(ninRaw)
        var sex = "Male"
        if (nin.startsWith("CF") || nin.startsWith("AF") || nin.startsWith("PF")) {
            sex = "Female"
        }

        return CardRecord(
            surname = surname,
            givenName = givenName,
            otherName = otherName,
            sex = sex,
            dateOfBirth = dob,
            nin = nin,
            cardNumber = cardNo,
            source = "Native PDF417 Barcode"
        )
    }

    private fun decodeBase64Utf8(encoded: String): String {
        return try {
            val bytes = Base64.decode(encoded.trim(), Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            encoded.trim().uppercase()
        }
    }

    fun parseMrzLines(lines: List<String>): CardRecord? {
        val candidates = lines.map { it.trim().replace("\\s+".toRegex(), "").uppercase().replace('€', 'C') }
            .filter { it.contains("UGA") || it.contains("<") || it.contains("CM0") || it.contains("CF0") || it.contains("IDUGA") }

        if (candidates.isEmpty()) return null

        var line1: String? = null
        var line2: String? = null
        var line3: String? = null

        for (l in candidates) {
            if (l.contains("IDUGA") || (l.length >= 25 && (l.contains("CM") || l.contains("CF") || l.startsWith("UGA")))) {
                line1 = l
            } else if (l.matches(".*\\d{6}[MF\\d]\\d{6}UGA.*".toRegex()) || (l.length >= 20 && l.contains("UGA"))) {
                line2 = l
            } else if (l.contains("<<") || (l.length >= 15 && l.contains("<"))) {
                line3 = l
            }
        }

        if (line1 == null || line3 == null) {
            if (candidates.size >= 3) {
                line1 = candidates[0]; line2 = candidates[1]; line3 = candidates[2]
            } else if (candidates.size >= 2) {
                line1 = candidates[0]; line3 = candidates[1]
            } else {
                return null
            }
        }

        var cardNumber = ""
        var nin = ""
        val m1Pattern = Pattern.compile("IDUGA(?<cardNo>\\d{9})\\d(?<nin>[A-Z0-9<]{14,15})")
        val matcher1 = m1Pattern.matcher(line1)
        if (matcher1.find()) {
            cardNumber = matcher1.group("cardNo") ?: ""
            nin = normalizeNinCandidate(matcher1.group("nin")?.replace("<", ""))
        } else {
            val cardMatch = Pattern.compile("\\d{9,10}").matcher(line1)
            if (cardMatch.find()) cardNumber = cardMatch.group(0) ?: ""
            val ninMatch = Pattern.compile("[A-Z]{2}\\d{8,9}[A-Z0-9]{3,4}").matcher(line1)
            if (ninMatch.find()) nin = normalizeNinCandidate(ninMatch.group(0))
        }

        var dob = ""
        var sex = "Male"
        if (line2 != null) {
            val m2Pattern = Pattern.compile("(?<dob>\\d{6})\\d(?<sexChar>[MF<])(?<exp>\\d{6})\\dUGA")
            val matcher2 = m2Pattern.matcher(line2)
            if (matcher2.find()) {
                val dobStr = matcher2.group("dob") ?: ""
                if (dobStr.length == 6) {
                    val yy = dobStr.substring(0, 2).toIntOrNull() ?: 0
                    val year = if (yy <= 30) 2000 + yy else 1900 + yy
                    dob = "$year-${dobStr.substring(2, 4)}-${dobStr.substring(4, 6)}"
                }
                val sexChar = matcher2.group("sexChar")
                if (sexChar == "F") sex = "Female"
                else if (sexChar == "M") sex = "Male"
            }
        }

        if (nin.startsWith("CF") || nin.startsWith("AF") || nin.startsWith("PF")) {
            sex = "Female"
        }

        var surname = ""
        var givenName = ""
        var otherName = ""
        val clean3 = line3.replace("<+$".toRegex(), "").replace("\\s+".toRegex(), "")
        if (clean3.contains("<<")) {
            val parts = clean3.split("<<")
            if (parts.isNotEmpty()) surname = cleanMrzNameToken(parts[0].replace("<", " "))
            if (parts.size >= 2) {
                val givenParts = parts[1].split("<")
                givenName = cleanMrzNameToken(givenParts[0])
                if (givenParts.size > 1) {
                    otherName = givenParts.subList(1, givenParts.size)
                        .map { cleanMrzNameToken(it) }.filter { it.isNotEmpty() }.joinToString(" ")
                }
            }
        } else {
            val parts = clean3.split("<").map { cleanMrzNameToken(it) }.filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) surname = parts[0]
            if (parts.size >= 2) givenName = parts[1]
            if (parts.size >= 3) otherName = parts.subList(2, parts.size).joinToString(" ")
        }

        return CardRecord(
            surname = surname,
            givenName = givenName,
            otherName = otherName,
            sex = sex,
            dateOfBirth = dob,
            nin = nin,
            cardNumber = cardNumber,
            source = "Native MRZ OCR"
        )
    }
}
