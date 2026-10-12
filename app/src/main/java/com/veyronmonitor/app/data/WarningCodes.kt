package com.veyronmonitor.app.data

/** Warning/fault code -> human-readable message, taken directly from the real i.Solar app. */
object WarningCodes {

    private val EVENT = mapOf(
        "001" to "Line Loss", "002" to "Solar1 Loss", "003" to "Solar2 Loss", "004" to "Solar3 Loss",
        "005" to "Battery Loss", "006" to "OPVShort", "007" to "Over temperature", "008" to "Fan locked",
        "009" to "Battery under shutdown", "010" to "Battery derating", "011" to "Overload",
        "012" to "Eeprom fault", "013" to "Power limit", "014" to "PV voltage high",
        "015" to "MPPT overload warning", "016" to "Battery too low to charge",
        "017" to "PV Voltage Over 1", "018" to "PV Voltage Over 2", "019" to "PV Voltage Over 3",
        "020" to "Bus Over", "021" to "Bus Under", "022" to "Bus Soft Failed", "023" to "Voltage Low",
        "024" to "Voltage High", "025" to "Battery Voltage Low", "026" to "Battery Voltage High",
        "027" to "Battery Low in Hybrid Mode", "028" to "Battery Under", "029" to "Battery Derating",
        "030" to "Battery equalization", "031" to "Battery Weak", "032" to "Fan Locked",
        "033" to "Over Current", "034" to "Soft Failed", "035" to "Self Test Failed",
        "036" to "OP DC Voltage Over", "037" to "Current Sensor Fail", "038" to "Grid Voltage High Loss",
        "039" to "Grid Voltage Low Loss", "040" to "Grid Frequency High Loss",
        "041" to "Grid Frequency Low Loss", "042" to "Grid Voltage Input Loss",
        "043" to "Grid Frequency Input Loss", "044" to "Grid Voltage Average Over",
        "045" to "Grid Input Island", "046" to "Grid Input Phase Dislocation", "047" to "EPO Active",
        "048" to "Grid Input Wave Loss"
    )

    private val FAULT = mapOf(
        "001" to "Fan is locked", "002" to "Battery voltage is too high", "003" to "Battery voltage is too low",
        "004" to "Output short circuited", "005" to "Output voltage is too high", "006" to "Overload timeout",
        "007" to "Over Temperature", "008" to "Over current inverter", "009" to "Bus voltage is too high",
        "010" to "Bus soft start failed", "011" to "Inverter soft start failed", "012" to "Self-test failed",
        "013" to "Over DC voltage on output of inverter", "014" to "Battery connection is open",
        "015" to "Current sensor failed", "016" to "Output voltage is too low",
        "017" to "Inverter negative power", "018" to "Parallel version different",
        "019" to "Output circuit failed", "020" to "CAN communication failed",
        "021" to "Parallel host line lost", "022" to "Parallel synchronized signal lost",
        "023" to "Parallel battery voltage detect different",
        "024" to "Parallel Line voltage or frequency detect different",
        "025" to "Parallel Line input current unbalanced", "026" to "Parallel output setting different",
        "027" to "Inverter relay work abnormal",
        "028" to "Current sample abnormal when inverter doesn't work", "029" to "SPS power voltage abnormal",
        "030" to "Solar input current exceed upper limit", "031" to "Leakage current exceed permit range",
        "032" to "Solar insulation resistance too low",
        "033" to "Inverter DC current exceed permit range when feed power",
        "034" to "AC input voltage/frequency mismatch between master and slave CPU",
        "035" to "Leakage current detect circuit abnormal when inverter doesn't work",
        "036" to "AC input ground wire loss", "037" to "Control board wiring error",
        "038" to "AC N wire current over", "039" to "Negative power detected",
        "040" to "Driver signal lost from relay board", "041" to "O/P current detection abnormal",
        "042" to "HOST lost", "043" to "SYN lost"
    )

    private fun normalize(code: String): String = code.trim().padStart(3, '0')

    fun warningMessage(code: String): String = EVENT[normalize(code)] ?: "Warning code $code"
    fun faultMessage(code: String): String = FAULT[normalize(code)] ?: "Fault code $code"
}
