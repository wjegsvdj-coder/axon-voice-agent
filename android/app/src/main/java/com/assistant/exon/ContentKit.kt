package com.assistant.exon

/**
 * Offline content-creation engine: hooks, titles, script beats, shot lists, captions.
 * Works with zero internet; the brain (if configured) can refine the output.
 */
object ContentKit {

    private val hookPatterns = listOf(
        "Stop scrolling if you %s",
        "The %s mistake almost everyone makes",
        "Nobody tells you this about %s",
        "This is why %s keeps going wrong",
        "%s in 30 seconds - no fluff",
        "I checked the data on %s. It is worse than you think.",
        "Three numbers that explain %s",
        "What %s actually costs you",
        "Banks hate that people understand %s",
        "Before you spend on %s, watch this"
    )

    private val titlePatterns = listOf(
        "%s: what the data actually says",
        "The hidden economics of %s",
        "Why %s is not what you were told",
        "%s explained in under a minute",
        "The %s trap nobody warns you about",
        "How %s really works",
        "%s: the numbers nobody checks",
        "3 things about %s that change the math"
    )

    fun hooks(topic: String): String =
        hookPatterns.take(6).mapIndexed { i, p -> "${i + 1}. " + String.format(p, topic) }.joinToString("\n")

    fun titles(topic: String): String =
        titlePatterns.take(6).mapIndexed { i, p -> "${i + 1}. " + String.format(p, topic) }.joinToString("\n")

    fun script(topic: String, seconds: Int = 40): String {
        val beats = listOf(
            "0-2s HOOK: the strongest verified number or contradiction about $topic. One sentence, no intro.",
            "2-6s STAKES: who it affects and how much money is involved.",
            "6-15s MECHANISM: how $topic actually works, step by step, plain language.",
            "15-25s PROOF: exact figure with its source named on screen and in the description.",
            "25-33s TENSION: the counter-argument or the catch people miss.",
            "33-40s CLOSE: tie back to the hook, one takeaway, one follow ask."
        )
        return "Script skeleton ($seconds sec) for: $topic\n" + beats.joinToString("\n")
    }

    fun shots(topic: String): String = listOf(
        "SCENE 1 | 0-2s | Hook card: one bold line, high contrast, fast zoom. SFX: riser.",
        "SCENE 2 | 2-6s | Stat card: the key number, source line small below. Cut on the beat.",
        "SCENE 3 | 6-15s | Mechanism diagram: 2-3 nodes max, each labelled with the exact term.",
        "SCENE 4 | 15-25s | Chart: two bars only (you vs them), values printed, source on screen.",
        "SCENE 5 | 25-33s | Contrast card: myth vs reality for $topic.",
        "SCENE 6 | 33-40s | Close card: takeaway + follow, same palette as scene 1 for a loop feel."
    ).joinToString("\n")

    fun caption(topic: String, source: String = ""): String =
        "$topic, explained with numbers.\n\n" +
        (if (source.isNotBlank()) "Source: $source\n\n" else "Sources in the video and description.\n\n") +
        "Educational only, not financial advice.\n\n" +
        "#shorts #finance #money #${
            topic.lowercase().replace(" ", "").take(20)
        }"

    fun pack(topic: String): String = buildString {
        appendLine("CONTENT PACK - $topic")
        appendLine()
        appendLine("HOOKS"); appendLine(hooks(topic)); appendLine()
        appendLine("TITLES"); appendLine(titles(topic)); appendLine()
        appendLine(script(topic)); appendLine()
        appendLine("SHOT LIST"); appendLine(shots(topic)); appendLine()
        appendLine("CAPTION"); appendLine(caption(topic))
    }
}
