package ani.sanin.parsers

/**
 * Recovers MKissa's build metadata from the site's crypto chunk.
 *
 * The chunk is a normal SvelteKit bundle except for one module whose string literals live in
 * shuffled lookup tables. Each table is read behind a pair of tiny decoder functions: a "base"
 * decoder subtracts a baked-in offset before indexing the table, and "alias" decoders call a base
 * with a shifted argument. Because the shuffles are per-build, the correct rotation is unknown up
 * front — but exactly one rotation makes the four seed fragments decode to valid 12-character
 * base64, which pins both the rotation and (with it) the numeric `buildId`.
 */
internal object MkissaBundle {

    class BuildInfo(
        val buildId: String,
        val seeds: List<String>,
        val config: MkissaCrypto.Config,
    )

    /**
     * The build this parser was written against, used when the crypto chunk cannot be reached.
     *
     * Scraping the bundle is only a way to survive the site rotating its build: the seeds and
     * config are per-build, but nothing else about the request changes. The bundle lives on a
     * separate CDN that is not always reachable from a phone's network, and a stalled fetch there
     * would otherwise block playback outright even though the API host is fine. Verified against
     * the live chunk: rotation 194, `md=Tt(65)`, and a config that matches [MkissaCrypto.Config]
     * exactly, so prefer the scrape and keep this only as a floor.
     */
    val KNOWN_GOOD = BuildInfo(
        buildId = "178",
        seeds = listOf("BEhxxUiiZHI=", "j7jB8srL7lk=", "OOK1Z/briyE=", "0yegxzQpyVA="),
        config = MkissaCrypto.Config(),
    )

    private class Base(val table: String, val offset: Int)
    private class Alias(val base: String, val argIndex: Int, val delta: Int)

    private class Decoders(
        val tables: Map<String, List<String>>,
        val bases: Map<String, Base>,
        val aliases: Map<String, Alias>,
    )

    fun parse(js: String): BuildInfo? {
        val d = decodersFrom(js) ?: return null
        val (rotation, seeds) = findSeeds(js, d) ?: return null
        val buildId = findBuildId(js, d, rotation) ?: return null
        return BuildInfo(buildId, seeds, readConfig(js, d, rotation))
    }

    // ---------------------------------------------------------------- decoders

    /**
     * The entry bundle only lists its sibling chunks; which one carries the crypto code is decided
     * at runtime, so callers have to fetch the candidates and look for [CRYPTO_CHUNK_MARKER].
     */
    fun chunkRefs(entryJs: String): List<String> =
        CHUNK_REF_REGEX.findAll(entryJs).map { it.groupValues[1] }.distinct().toList()

    private fun decodersFrom(js: String): Decoders? {
        val tables = readTables(js)
        val bases = BASE_DECODER_REGEX.findAll(js).associate { m ->
            m.groupValues[1] to Base(m.groupValues[4], fold(m.groupValues[3]))
        }
        if (bases.isEmpty()) return null
        val aliases = buildMap {
            bases.keys.forEach { put(it, Alias(it, 0, 0)) }
            ALIAS_DECODER_REGEX.findAll(js).forEach { m ->
                val (name, firstParam, _, callee, arg, delta) = m.destructured
                if (callee !in bases) return@forEach
                put(
                    name,
                    Alias(callee, if (arg == firstParam) 0 else 1, if (delta.isEmpty()) 0 else foldDelta(delta)),
                )
            }
        }
        return Decoders(tables, bases, aliases)
    }

    private fun resolve(call: String, rotation: Int, d: Decoders): String? {
        val match = CALL_REGEX.matchEntire(call) ?: return null
        val alias = d.aliases[match.groupValues[1]] ?: return null
        val base = d.bases[alias.base] ?: return null
        val table = d.tables[base.table]?.takeIf { it.isNotEmpty() } ?: return null
        val args = listOfNotNull(
            match.groupValues[2].toIntOrNull(),
            match.groupValues[3].toIntOrNull(),
        )
        val arg = args.getOrNull(alias.argIndex) ?: return null
        val index = arg + alias.delta - base.offset + rotation
        return table[((index % table.size) + table.size) % table.size]
    }

    private fun readTables(js: String): Map<String, List<String>> = buildMap {
        for (match in TABLE_HEAD_REGEX.findAll(js)) {
            // The pattern stops *on* the `[`, so the array body starts one character later.
            readStringArray(js, match.range.last)?.let { put(match.groupValues[1], it) }
        }
    }

    private fun readStringArray(js: String, open: Int): List<String>? {
        val items = mutableListOf<String>()
        var i = open + 1
        while (i < js.length) {
            when (val c = js[i]) {
                ']' -> return items
                ',', ' ' -> i++
                '"', '\'' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < js.length && js[i] != c) {
                        if (js[i] == '\\') {
                            if (i + 1 >= js.length) return null
                            sb.append(js[i + 1])
                            i += 2
                        } else {
                            sb.append(js[i])
                            i++
                        }
                    }
                    if (i >= js.length) return null
                    i++
                    items.add(sb.toString())
                }
                else -> return null
            }
        }
        return null
    }

    // ------------------------------------------------------------------ seeds

    /** Returns the unique rotation that decodes the seed array, plus the seeds themselves. */
    private fun findSeeds(js: String, d: Decoders): Pair<Int, List<String>>? {
        for (match in SEED_ARRAY_REGEX.findAll(js)) {
            // Each element is one or more decoder calls joined by `+`; the top level commas have
            // to be split first because the calls contain commas of their own.
            val groups = splitTopLevel(match.groupValues[1], ',')
                .map { element -> element.split('+').map { it.trim() } }
            if (groups.size != MkissaCrypto.SEED_COUNT) continue
            if (groups.any { calls -> calls.isEmpty() || calls.any { CALL_REGEX.matchEntire(it) == null } }) continue

            val table = CALL_REGEX.find(groups.first().first())
                ?.let { d.aliases[it.groupValues[1]] }
                ?.let { d.tables[d.bases[it.base]?.table] }
                ?: continue

            val matches = table.indices.mapNotNull { rotation ->
                seedsAt(groups, rotation, d)?.let { rotation to it }
            }
            // Ambiguity here means we picked up an unrelated array; a real seed set is unique.
            if (matches.size == 1) return matches.first()
        }
        return null
    }

    private fun seedsAt(groups: List<List<String>>, rotation: Int, d: Decoders): List<String>? {
        val seeds = groups.map { group ->
            val sb = StringBuilder()
            for (call in group) {
                sb.append(resolve(call, rotation, d) ?: return null)
            }
            val value = sb.toString()
            if (!SEED_REGEX.matches(value)) return null
            value
        }
        return seeds.takeIf { it.size == MkissaCrypto.SEED_COUNT }
    }

    // --------------------------------------------------------------- buildId

    private fun findBuildId(js: String, d: Decoders, rotation: Int): String? {
        // The build id lives in a module-level constant that is assigned from a decoder call and then
        // used as the default value of a crypto function parameter. There is usually more than one
        // such constant, so every candidate is tried and the rotation already pinned by the seeds
        // decides which one decodes to a build-id shaped value.
        val defaulted = Regex("""function\s+$IDENT\s*\(\s*\w+\s*=\s*($IDENT)\s*[,)]""")
            .findAll(js)
            .map { it.groupValues[1] }
            .filter { it.isNotEmpty() }
            .toSet()

        val assignmentOf = { name: String ->
            Regex("""\b${Regex.escape(name)}\s*=\s*($CALL_PATTERN)(?![A-Za-z0-9_])""").findAll(js)
                .map { it.groupValues[1] }
                .toList()
        }

        for (name in defaulted) {
            for (call in assignmentOf(name)) {
                val decoded = resolve(call, rotation, d)
                if (decoded != null && BUILD_ID_REGEX.matches(decoded)) return decoded
            }
        }

        // Fallback: any bare decoder call in the chunk that decodes to a build id.
        for (match in CALL_REGEX.findAll(js)) {
            val call = match.value
            if (call.contains("+")) continue
            val decoded = resolve(call, rotation, d) ?: continue
            if (!BUILD_ID_REGEX.matches(decoded)) continue
            val before = js.substring((match.range.first - 20).coerceAtLeast(0), match.range.first)
            if (before.contains("sf=") || before.contains("kd=")) continue
            return decoded
        }
        return null
    }

    // ----------------------------------------------------------------- config

    /**
     * Reads the site's `$f` config block. The numeric fields are plain literals, but `bootPrefix`
     * and `parts` are concatenations of decoder calls, so they go through [resolve].
     */
    private fun readConfig(js: String, d: Decoders, rotation: Int): MkissaCrypto.Config {
        val fallback = MkissaCrypto.Config()

        fun intField(name: String, current: Int): Int =
            Regex("""\b$name\s*:\s*(-?\d+)""").find(js)?.groupValues?.get(1)?.toIntOrNull() ?: current

        val join = Regex("""\bjoin\s*:\s*\x22([^\x22]*)\x22""").find(js)?.groupValues?.get(1)
            ?: fallback.join

        val bootPrefix = Regex("""\bbootPrefix\s*:\s*((?:$CALL_PATTERN|"[^"]*")(?:\+(?:$CALL_PATTERN|"[^"]*"))*)""")
            .find(js)?.groupValues?.get(1)?.let { evalExpression(it, rotation, d) }
            ?.takeIf { it.isNotEmpty() }
            ?: fallback.bootPrefix

        val parts = Regex("""\bparts\s*:\s*\[((?:$CALL_PATTERN|"[^"]*")(?:\+(?:$CALL_PATTERN|"[^"]*"))*(?:,(?:$CALL_PATTERN|"[^"]*")(?:\+(?:$CALL_PATTERN|"[^"]*"))*)*)]\]""")
            .find(js)?.groupValues?.get(1)
            ?.let { body -> splitTopLevel(body, ',').mapNotNull { evalExpression(it.trim(), rotation, d) } }
            ?.takeIf { it.size >= 3 }
            ?: fallback.parts

        return MkissaCrypto.Config(
            saltMul = intField("saltMul", fallback.saltMul),
            saltAdd = intField("saltAdd", fallback.saltAdd),
            fragMul = intField("fragMul", fallback.fragMul),
            fragAdd = intField("fragAdd", fallback.fragAdd),
            bootPrefix = bootPrefix,
            join = join,
            parts = parts,
        )
    }

    /** Evaluates a `+`-joined chain of decoder calls and quoted literals. */
    private fun evalExpression(expr: String, rotation: Int, d: Decoders): String? {
        val sb = StringBuilder()
        for (term in splitTopLevel(expr, '+')) {
            val piece = term.trim()
            when {
                piece.length >= 2 && (piece.startsWith("\"") && piece.endsWith("\"") ||
                    piece.startsWith("'") && piece.endsWith("'")
                ) -> sb.append(piece.substring(1, piece.length - 1))

                CALL_REGEX.matchEntire(piece) != null -> sb.append(resolve(piece, rotation, d) ?: return null)
                else -> return null
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------- arithmetic

    private fun splitTopLevel(value: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (char in value) {
            when (char) {
                '(' -> depth++
                ')' -> depth--
            }
            if (char == delimiter && depth == 0) {
                parts.add(current.toString())
                current.clear()
            } else {
                current.append(char)
            }
        }
        parts.add(current.toString())
        return parts
    }

    private fun foldDelta(expression: String): Int = fold(MEMBER_ACCESS_REGEX.replace(expression, "$1"))

    private fun fold(expression: String): Int {
        var total = 0
        for (term in TERM_REGEX.findAll(expression.replace(" ", "")).map { it.value }) {
            var sign = 1
            var body = term
            while (body.startsWith('+') || body.startsWith('-')) {
                if (body.startsWith('-')) sign = -sign
                body = body.substring(1)
            }
            var negative = sign < 0
            var digits = body
            while (digits.startsWith('+') || digits.startsWith('-')) {
                if (digits.startsWith('-')) negative = !negative
                digits = digits.substring(1)
            }
            val magnitude = digits.toLongOrNull() ?: return 0
            var value = if (negative) -magnitude else magnitude
            val rest = body.substringAfter("*", "")
            if (rest.isNotEmpty()) {
                for (factor in rest.split("*")) {
                    value *= parseFactor(factor) ?: return 0
                }
            }
            if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return 0
            total += value.toInt()
        }
        return total
    }

    private fun parseFactor(factor: String): Long? {
        var negative = false
        var digits = factor
        while (digits.startsWith('+') || digits.startsWith('-')) {
            if (digits.startsWith('-')) negative = !negative
            digits = digits.substring(1)
        }
        val magnitude = digits.toLongOrNull() ?: return null
        return if (negative) -magnitude else magnitude
    }

    private val BUILD_ID_REGEX = Regex("""\d{2,10}""")
    private val SEED_REGEX = Regex("""[A-Za-z0-9+/]{11}=""")

    private val IDENT = """[${'$'}A-Za-z0-9_]+"""
    private val CALL_PATTERN = """($IDENT)\(\s*(-?\d+)\s*(?:,\s*(-?\d+)\s*)?\)"""

    // `\x5B` is the opening bracket: a raw string cannot end with a literal `"` right before its
    // own closing delimiter, and this pattern has to stop exactly on the `[`.
    private val TABLE_HEAD_REGEX =
        Regex("""function ($IDENT)\(\)\s*\{\s*(?:const|let|var)\s+$IDENT\s*=\s*\x5B""")
    private val BASE_DECODER_REGEX =
        Regex("""function ($IDENT)\(($IDENT)(?:,$IDENT)*\)\{return \2=\2-\(?([-\d+*\s]+?)\)?,($IDENT)\(\)\[\2\]\}""")
    private val ALIAS_DECODER_REGEX =
        Regex("""function ($IDENT)\(($IDENT),($IDENT)\)\{return ($IDENT)\(($IDENT)((?:[-+](?:[\d+*\s-]+|\{[^{}]*\}[._][A-Za-z0-9_${'$'}]+))?)\)\}""")
    private val CALL_REGEX = Regex(CALL_PATTERN)
    private val SEED_ARRAY_REGEX = Regex("""=\[((?:$CALL_PATTERN(?:\+$CALL_PATTERN)*,){3}$CALL_PATTERN(?:\+$CALL_PATTERN)*)]""")
    private val MEMBER_ACCESS_REGEX = Regex("""\{[^{}]*:(-?\d+)\}[._][A-Za-z0-9_${'$'}]+""")
    private val TERM_REGEX = Regex("""[-+]*\d+(?:\*[-+]*\d+)*""")

    /** Entry JS referenced by the site's HTML, e.g. `.../_app/immutable/entry/app.BawrX-C8.js`. */
    val APP_ENTRY_REGEX = Regex("""import\("([^"]*/entry/app\.[^"]*\.js)"\)""")

    /** Relative chunk imports listed inside that entry module. */
    val CHUNK_REF_REGEX = Regex("""["'](\.\.?/[\w./-]+\.js)["']""")

    /** Only one chunk contains the crypto module; this marker is cheap and stable. */
    const val CRYPTO_CHUNK_MARKER = "aaReq"
}
