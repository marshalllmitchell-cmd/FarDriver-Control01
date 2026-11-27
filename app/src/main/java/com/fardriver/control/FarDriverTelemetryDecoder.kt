package com.fardriver.control

import kotlin.math.sqrt

/** Read-only decoder derived from MotorNet6 GraphPage.cs.
 *  New-format 0x80..0xBF packets are mapped via FlashReadAddr; the original
 *  GraphPage does not checksum-check these packets, so engineering readings
 *  are explicitly tagged SOURCE_UNVERIFIED until independently validated.
 */
object FarDriverTelemetryDecoder {
    enum class Integrity { CHECKSUM_OK, CHECKSUM_FAILED, SOURCE_UNVERIFIED, BAD_ENVELOPE }
    data class Result(val integrity: Integrity, val family: String, val frameId: Int?,
        val fields: Map<String, Double> = emptyMap(), val notes: String = "")
    data class LiveValues(
        val batteryVolts: Double? = null, val batteryAmps: Double? = null,
        val powerKw: Double? = null, val phaseAAmps: Double? = null,
        val phaseCAmps: Double? = null, val motorSpeedRaw: Double? = null,
        val controllerC: Double? = null, val motorC: Double? = null,
        val throttleVolts: Double? = null, val updatedAtMs: Long = 0L,
        val sourceVerifiedOnly: Boolean = true
    )
    /** Retains the last known field from each multiplexed message. */
    fun merge(previous: LiveValues, result: Result, nowMs: Long): LiveValues {
        if (result.integrity == Integrity.BAD_ENVELOPE || result.integrity == Integrity.CHECKSUM_FAILED || result.fields.isEmpty()) return previous
        val f = result.fields
        return previous.copy(
            batteryVolts = f["batteryVolts"] ?: previous.batteryVolts,
            batteryAmps = f["batteryAmps"] ?: previous.batteryAmps,
            powerKw = f["powerKw"] ?: previous.powerKw,
            phaseAAmps = f["phaseAAmps"] ?: previous.phaseAAmps,
            phaseCAmps = f["phaseCAmps"] ?: previous.phaseCAmps,
            motorSpeedRaw = f["motorSpeedRaw"] ?: previous.motorSpeedRaw,
            controllerC = f["controllerC"] ?: previous.controllerC,
            motorC = f["motorC"] ?: previous.motorC,
            throttleVolts = f["throttleVolts"] ?: previous.throttleVolts,
            updatedAtMs = nowMs,
            sourceVerifiedOnly = result.integrity != Integrity.CHECKSUM_OK
        )
    }
    private val flashAddresses = intArrayOf(
        226,232,238,0,6,12,18,226,232,238,24,30,36,42,226,232,238,48,93,99,
        105,226,232,238,124,130,136,142,226,232,238,148,154,160,166,226,232,
        238,172,178,184,190,226,232,238,196,202,208,226,232,238,214,220,244,250)
    private fun u(b: Byte) = b.toInt() and 255
    private fun le16(b: ByteArray, i: Int) = u(b[i]) or (u(b[i+1]) shl 8)
    private fun be16(b: ByteArray, i: Int) = (u(b[i]) shl 8) or u(b[i+1])
    private fun signed16(n: Int) = if (n >= 32768) n - 65536 else n
    private fun be24(b: ByteArray, i: Int) = (u(b[i]) shl 16) or (u(b[i+1]) shl 8) or u(b[i+2])
    fun decode(frame: ByteArray): Result {
        if (frame.size != 16 || u(frame[0]) != 0xAA)
            return Result(Integrity.BAD_ENVELOPE, "unknown", null)
        val id = u(frame[1])
        // GraphPage.cs: (arg[1] & 0xC0) == 128 is the newer flash-index family.
        if ((id and 0xC0) == 0x80) {
            val index = id and 0x7F
            if (index !in flashAddresses.indices)
                return Result(Integrity.SOURCE_UNVERIFIED, "flash-index", id, notes = "Unknown index")
            val addr = flashAddresses[index]
            val f = when (addr) {
                226 -> mapOf("motorSpeedRaw" to signed16(le16(frame,8)).toDouble())
                232 -> {
                    val v = signed16(le16(frame,2)) / 10.0
                    val a = signed16(le16(frame,6)) / 4.0
                    mapOf("batteryVolts" to v, "batteryAmps" to a,
                        "powerKw" to (v*a/1000.0), "throttleRaw" to le16(frame,12).toDouble())
                }
                238 -> mapOf("phaseAAmps" to 1.953125*sqrt(be24(frame,6).toDouble()),
                    "phaseCAmps" to 1.953125*sqrt(be24(frame,9).toDouble()),
                    "turnsRaw" to le16(frame,4).toDouble())
                214 -> mapOf("controllerC" to signed16(le16(frame,12)).toDouble())
                244 -> mapOf("motorC" to signed16(le16(frame,2)).toDouble(),
                    "batterySocPercent" to u(frame[5]).toDouble())
                130 -> mapOf("throttleVolts" to (le16(frame,2)*0.01))
                18 -> mapOf("polePairs" to u(frame[6]).toDouble())
                else -> emptyMap()
            }
            return Result(Integrity.SOURCE_UNVERIFIED, "flash-index address=$addr", id, f,
                "Source does not verify received CRC in this branch")
        }
        // GraphPage.cs old family: 16-bit sum of bytes 0..13, high byte at 14.
        val sum = (0..13).sumOf { u(frame[it]) }
        val transmitted = be16(frame,14)
        if (sum != transmitted)
            return Result(Integrity.CHECKSUM_FAILED, "legacy", id, notes = "sum=$sum received=$transmitted")
        val f = when (id) {
            0 -> mapOf("motorSpeedRaw" to signed16(be16(frame,6)).toDouble())
            1 -> {
                val v = signed16(be16(frame,2))/10.0
                val a = signed16(be16(frame,4))/4.0
                mapOf("batteryVolts" to v,"batteryAmps" to a,"powerKw" to v*a/1000.0,
                    "throttleRaw" to be16(frame,12).toDouble())
            }
            2 -> mapOf("phaseAAmps" to 1.953125*sqrt(be24(frame,2).toDouble()),
                "phaseCAmps" to 1.953125*sqrt(be24(frame,9).toDouble()))
            4 -> mapOf("controllerC" to (if (u(frame[4]) > 200) u(frame[4])-256 else u(frame[4])).toDouble())
            10 -> mapOf("batterySocPercent" to u(frame[10]).toDouble())
            13 -> mapOf("motorC" to u(frame[2]).toDouble(),
                "throttleVolts" to be16(frame,4)*4.95/4096.0)
            else -> emptyMap()
        }
        return Result(Integrity.CHECKSUM_OK,"legacy",id,f)
    }
}
