package com.cpagency.wifimanager

/**
 * Reads the data embedded inside the Huawei web pages.
 *
 * Every admin page on this router puts its data in the HTML as JavaScript, e.g.
 *
 *   function stDeviceInfo(domain, SerialNumber, HardwareVersion, ...) { ... }
 *   var deviceInfo = new stDeviceInfo("InternetGatewayDevice.DeviceInfo", "4857...", "AC7\x2eA", ...);
 *   var cpuUsed = '23%';
 *
 * So instead of hard-coding every page, we:
 *  1. collect every `function Name(a, b, c)` signature (gives us the field names),
 *  2. collect every `new Name("v1", "v2", ...)` call that only has literal values,
 *  3. pair them up -> a record with named fields,
 *  4. also keep simple `var x = '...'` values.
 *
 * Pure Kotlin (no Android classes) so it can be unit-tested on a normal JVM.
 */
class HwRecord(val cls: String, val fields: LinkedHashMap<String, String>) {
    operator fun get(key: String): String =
        fields[key] ?: fields.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value ?: ""

    fun has(key: String) = fields.containsKey(key) || fields.keys.any { it.equals(key, ignoreCase = true) }
}

class PageData {
    /** class name -> field names, from `function X(...)` */
    val sigs = HashMap<String, List<String>>()

    /** signatures per source, because two pages can define the same class name differently */
    internal val sourceSigs = ArrayList<Map<String, List<String>>>()

    private class Instance(val cls: String, val args: List<String>, val src: Int)

    /** every literal `new X(...)` call, in page order */
    private val instances = ArrayList<Instance>()

    /** var name -> all literal values it was assigned (in order) */
    val vars = LinkedHashMap<String, MutableList<String>>()

    /** source path -> raw response text */
    val texts = LinkedHashMap<String, String>()

    /** source path -> error message, if that source failed to load */
    val errors = LinkedHashMap<String, String>()

    fun add(path: String, text: String) {
        texts[path] = text
        HwParser.parseInto(text, this)
    }

    internal fun addInstance(cls: String, args: List<String>) {
        instances.add(Instance(cls, args, sourceSigs.size - 1))
    }

    /** All records of a class, fields named using the matching function signature. */
    fun all(cls: String): List<HwRecord> =
        instances.filter { it.cls == cls }.map { toRecord(it) }

    fun first(cls: String): HwRecord? = all(cls).firstOrNull()

    /** Every record in the page(s), in order. */
    fun allRecords(): List<HwRecord> = instances.map { toRecord(it) }

    fun classes(): List<String> = instances.map { it.cls }.distinct()

    /** Value of `var name = '...'` (idx = which assignment, if the page sets it more than once). */
    fun v(name: String, idx: Int = 0): String = vars[name]?.getOrNull(idx) ?: ""

    private fun toRecord(inst: Instance): HwRecord {
        val cls = inst.cls
        val args = inst.args
        // prefer the signature from the same page, then any page
        val names = sourceSigs.getOrNull(inst.src)?.get(cls) ?: sigs[cls] ?: emptyList()
        val map = LinkedHashMap<String, String>()
        args.forEachIndexed { i, value ->
            val key = names.getOrNull(i) ?: "field${i + 1}"
            map[key] = value
        }
        return HwRecord(cls, map)
    }
}

object HwParser {

    private val funcRegex = Regex("""function\s+(\w+)\s*\(([^)]*)\)""")
    private val newRegex = Regex("""new\s+(\w+)\s*\(""")
    private val varRegex = Regex(
        """\bvar\s+(\w+)\s*=\s*('(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*")\s*;"""
    )

    /** Built-in / UI helper classes that never hold router data. */
    private val ignoredClasses = setOf(
        "Array", "Object", "Date", "RegExp", "Image", "Option", "XMLHttpRequest",
        "ActiveXObject", "String", "Number", "Boolean", "Function", "Error"
    )

    private val literalWords = setOf("true", "false", "null", "undefined")

    fun parse(text: String): PageData = PageData().also { parseInto(text, it) }

    fun parseInto(text: String, data: PageData) {
        // 1. function signatures (first definition per page wins)
        val local = HashMap<String, List<String>>()
        for (m in funcRegex.findAll(text)) {
            val name = m.groupValues[1]
            if (local.containsKey(name)) continue
            val params = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (params.isNotEmpty() && params.all { it.matches(Regex("""\w+""")) }) {
                local[name] = params
                data.sigs.putIfAbsent(name, params)
            }
        }
        data.sourceSigs.add(local)

        // 2. `new X(...)` with only literal arguments
        for (m in newRegex.findAll(text)) {
            val cls = m.groupValues[1]
            if (cls in ignoredClasses) continue
            val args = readArgs(text, m.range.last + 1) ?: continue
            if (args.isEmpty()) continue
            data.addInstance(cls, args)
        }

        // 3. simple string vars
        for (m in varRegex.findAll(text)) {
            val raw = m.groupValues[2]
            val value = unescape(raw.substring(1, raw.length - 1))
            data.vars.getOrPut(m.groupValues[1]) { mutableListOf() }.add(value)
        }
    }

    /**
     * Reads a comma separated argument list starting just after '('.
     * Returns null if any argument is not a plain literal (string / number / true / false / null),
     * because those are JS code templates, not real data.
     */
    private fun readArgs(text: String, start: Int): List<String>? {
        val out = ArrayList<String>()
        var i = start
        var expectValue = true
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == ')' -> return out
                c == ',' -> {
                    if (expectValue) out.add("") // empty slot like (a,,b)
                    expectValue = true
                    i++
                }
                !expectValue -> return null
                c == '"' || c == '\'' -> {
                    val end = findStringEnd(text, i) ?: return null
                    out.add(unescape(text.substring(i + 1, end)))
                    i = end + 1
                    expectValue = false
                }
                c == '-' || c.isDigit() -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isDigit() || text[j] == '.')) j++
                    val num = text.substring(i, j)
                    if (num == "-") return null
                    out.add(num)
                    i = j
                    expectValue = false
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                    val word = text.substring(i, j)
                    if (word !in literalWords) return null
                    out.add(if (word == "null" || word == "undefined") "" else word)
                    i = j
                    expectValue = false
                }
                else -> return null
            }
        }
        return null
    }

    private fun findStringEnd(text: String, start: Int): Int? {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') { i += 2; continue }
            if (c == quote) return i
            if (c == '\n') return null
            i++
        }
        return null
    }

    /** Decodes the \x2e, é, \n ... escapes the router uses everywhere. */
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) { sb.append(c); i++; continue }
            val n = s[i + 1]
            when (n) {
                'x' -> {
                    val hex = s.substring(i + 2, minOf(i + 4, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 2) { sb.append(code.toChar()); i += 4 } else { sb.append(n); i += 2 }
                }
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 4) { sb.append(code.toChar()); i += 6 } else { sb.append(n); i += 2 }
                }
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { i += 2 }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }
}
