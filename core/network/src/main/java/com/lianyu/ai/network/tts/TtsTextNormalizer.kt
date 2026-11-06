package com.lianyu.ai.network.tts

/**
 * 朗读文本的读法处理（纯函数，只作用于送去合成的文本，聊天里显示的文字不受影响）：
 * - 颜文字、emoji 不读
 * - "～" "…" 换成短停顿
 * - 数字按口语读（时间、百分比、小数、年份、电话/长数字逐位读）
 * - 英文：常见词用对照表，短缩写按字母读，读不出的直接跳过（避免被读成乱码）
 */
object TtsTextNormalizer {
    private const val DIGITS = "零一二三四五六七八九"
    /** 数字 2 后面跟这些量词/名词时读"两" */
    private const val LIANG_NEXT = "个些天次只条位块本份张句回遍杯人双件间部对场"

    private val KAOMOJI_PARENS = Regex("""[（(]([^（）()]{1,16})[）)]""")
    private val KAOMOJI_WORDS = Regex(
        """(?<![A-Za-z])(?:QAQ|QwQ|QvQ|TAT|TvT|T_T|T\.T|OvO|OwO|UwU|orz|OTZ|Orz)(?![A-Za-z])"""
    )
    private val FACES = listOf(">_<", "^_^", "-_-", "=_=")

    private val RANGE_TILDE = Regex("""(\d)\s*[～~]\s*(\d)""")
    private val NEGATIVE = Regex("""(?<![\w])-(?=\d)""")
    private val TILDE = Regex("""[～~]+""")
    private val ELLIPSIS = Regex("""…+|\.{2,}|。{2,}""")

    private val TIME = Regex("""(?<![\d:])(\d{1,2}):(\d{2})(?![\d:])""")
    private val PERCENT = Regex("""(\d+(?:\.\d+)?)%""")
    private val YEAR = Regex("""(\d{4})年""")
    private val SLANG = Regex("""(?<![\d.])(?:520|521|1314|1314520)(?![\d.])""")
    private val DECIMAL = Regex("""(\d+)\.(\d+)""")
    private val INTEGER = Regex("""\d+""")

    private val ENGLISH = Regex("""[A-Za-z][A-Za-z']*""")
    private val SPACES = Regex("""[ \t]+""")
    private val COMMA_BEFORE_STOP = Regex("""[，、]+(?=[。！？!?；;])""")
    private val MULTI_COMMA = Regex("""[，、]{2,}""")
    private val LEADING_PUNCT = Regex("""^[，、。\s]+""")
    private val TRAILING_COMMA = Regex("""，+$""")

    private val WORDS = mapOf(
        "ok" to "欧克", "okay" to "欧克", "bye" to "拜拜", "byebye" to "拜拜",
        "hi" to "嗨", "hello" to "哈喽", "hey" to "嘿", "thanks" to "谢谢", "thankyou" to "谢谢",
        "sorry" to "抱歉", "love" to "爱", "kiss" to "亲亲", "wow" to "哇",
        "yes" to "是的", "no" to "不", "good" to "好", "night" to "晚上",
        "goodnight" to "晚安", "morning" to "早上", "haha" to "哈哈", "hehe" to "嘿嘿",
        "lol" to "哈哈", "emm" to "嗯", "emmm" to "嗯", "em" to "嗯",
        "mua" to "么么", "muah" to "么么", "wifi" to "无线网", "app" to "应用"
    )

    private val LETTER_NAMES = mapOf(
        'A' to "诶", 'B' to "比", 'C' to "西", 'D' to "迪", 'E' to "伊", 'F' to "艾弗",
        'G' to "吉", 'H' to "艾趣", 'I' to "爱", 'J' to "杰", 'K' to "开", 'L' to "艾勒",
        'M' to "艾姆", 'N' to "恩", 'O' to "欧", 'P' to "批", 'Q' to "丘", 'R' to "阿",
        'S' to "艾斯", 'T' to "提", 'U' to "优", 'V' to "维", 'W' to "达不溜",
        'X' to "艾克斯", 'Y' to "歪", 'Z' to "贼"
    )

    // ─────────────── 对外入口 ───────────────

    /** 去掉 emoji 和颜文字。应在"跳过括号内心戏"之前调用。 */
    fun stripKaomoji(text: String): String {
        var s = stripEmoji(text)
        s = KAOMOJI_PARENS.replace(s) { m -> if (isKaomojiInner(m.groupValues[1])) "" else m.value }
        s = KAOMOJI_WORDS.replace(s, "")
        for (face in FACES) s = s.replace(face, "")
        return s
    }

    /** 停顿 + 数字 + 英文 + 标点整理。 */
    fun normalize(text: String): String {
        var s = text
        s = RANGE_TILDE.replace(s) { m -> "${m.groupValues[1]}到${m.groupValues[2]}" }
        s = NEGATIVE.replace(s, "负")
        s = TILDE.replace(s, "，")
        s = ELLIPSIS.replace(s, "，")
        s = normalizeNumbers(s)
        s = normalizeEnglish(s)
        s = SPACES.replace(s, "")
        s = COMMA_BEFORE_STOP.replace(s, "")
        s = MULTI_COMMA.replace(s, "，")
        s = LEADING_PUNCT.replace(s, "")
        s = TRAILING_COMMA.replace(s.trim(), "。")
        return s.trim()
    }

    // ─────────────── 颜文字 ───────────────

    private fun stripEmoji(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isEmoji(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun isEmoji(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F1E6..0x1F1FF ||
            cp == 0xFE0F || cp == 0x200D || cp == 0x2B50 || cp == 0x2B55

    /** 括号里没有汉字，且含有"符号"（不全是字母数字/数字常用标点）→ 当作颜文字 */
    private fun isKaomojiInner(inner: String): Boolean {
        if (inner.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return false
        for (ch in inner) {
            if (ch.isLetterOrDigit() || ch.isWhitespace()) continue
            if (ch in ".,%:-/+") continue
            return true
        }
        return false
    }

    // ─────────────── 数字 ───────────────

    private fun normalizeNumbers(input: String): String {
        var s = input
        s = TIME.replace(s) { m ->
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            if (h > 23 || mi > 59) {
                m.value
            } else {
                val hour = if (h == 2) "两" else intToChinese(h.toLong())
                when {
                    mi == 0 -> "${hour}点整"
                    mi < 10 -> "${hour}点零${DIGITS[mi]}分"
                    else -> "${hour}点${intToChinese(mi.toLong())}分"
                }
            }
        }
        s = PERCENT.replace(s) { m -> "百分之" + numberText(m.groupValues[1]) }
        s = YEAR.replace(s) { m -> digitsToChinese(m.groupValues[1]) + "年" }
        s = SLANG.replace(s) { m -> digitsToChinese(m.value) }
        s = DECIMAL.replace(s) { m ->
            if (m.groupValues[1].length < 9) {
                intToChinese(m.groupValues[1].toLong()) + "点" + digitsToChinese(m.groupValues[2])
            } else {
                m.value
            }
        }
        val source = s
        s = INTEGER.replace(source) { m ->
            val t = m.value
            if (t.length >= 6 || (t.length >= 2 && t[0] == '0')) {
                // 电话、邮编、长编号：逐位读
                digitsToChinese(t)
            } else {
                val n = t.toLong()
                val next = source.getOrNull(m.range.last + 1)
                if (n == 2L && next != null && LIANG_NEXT.indexOf(next) >= 0) "两" else intToChinese(n)
            }
        }
        return s
    }

    private fun numberText(t: String): String {
        val dot = t.indexOf('.')
        return if (dot >= 0 && t.substring(0, dot).length < 9) {
            intToChinese(t.substring(0, dot).toLong()) + "点" + digitsToChinese(t.substring(dot + 1))
        } else if (dot >= 0) {
            digitsToChinese(t)
        } else {
            intToChinese(t.toLong())
        }
    }

    private fun digitsToChinese(t: String): String {
        val sb = StringBuilder(t.length)
        for (c in t) if (c in '0'..'9') sb.append(DIGITS[c - '0'])
        return sb.toString()
    }

    /** 0 ~ 9999万 的整数读法（口语："两百""一万零一""十二"） */
    private fun intToChinese(n: Long): String {
        if (n == 0L) return "零"
        if (n >= 100_000_000L) return digitsToChinese(n.toString())
        if (n >= 10_000L) {
            val wan = (n / 10_000L).toInt()
            val rest = (n % 10_000L).toInt()
            val sb = StringBuilder()
            sb.append(if (wan == 2) "两" else below10000(wan, true)).append("万")
            if (rest > 0) {
                if (rest < 1000) sb.append("零")
                sb.append(below10000(rest, false))
            }
            return sb.toString()
        }
        return below10000(n.toInt(), true)
    }

    private fun below10000(x: Int, top: Boolean): String {
        val d = intArrayOf(x / 1000, x / 100 % 10, x / 10 % 10, x % 10)
        val units = arrayOf("千", "百", "十", "")
        val sb = StringBuilder()
        var started = false
        var pendingZero = false
        for (i in 0..3) {
            val v = d[i]
            if (v == 0) {
                if (started) pendingZero = true
                continue
            }
            if (pendingZero) {
                sb.append("零")
                pendingZero = false
            }
            if (v == 1 && i == 2 && !started && top) {
                sb.append("十")
            } else {
                sb.append(if (v == 2 && i <= 1) "两" else DIGITS[v].toString()).append(units[i])
            }
            started = true
        }
        return sb.toString()
    }

    // ─────────────── 英文 ───────────────

    private fun normalizeEnglish(input: String): String = ENGLISH.replace(input) { m ->
        val word = m.value
        val mapped = WORDS[word.lowercase()]
        when {
            mapped != null -> mapped
            // 1~4 个大写字母的缩写（AI、PK、VIP）按字母读
            word == word.uppercase() && word.length in 1..4 && !word.contains('\'') -> spell(word)
            // 其他读不出的英文：跳过
            else -> ""
        }
    }

    private fun spell(word: String): String {
        val sb = StringBuilder()
        for (c in word) sb.append(LETTER_NAMES[c.uppercaseChar()] ?: "")
        return sb.toString()
    }
}
