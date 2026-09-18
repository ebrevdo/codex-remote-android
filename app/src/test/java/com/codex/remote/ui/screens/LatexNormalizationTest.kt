package com.codex.remote.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LatexNormalizationTest {
    @Test fun normalizesTheScreenshotFormulaAndTableHeader() {
        val source = """Use one tolerance:

\[
\varepsilon=\eta B,
\]

where \(B\) is the best continuation.

| \(\eta\) | Interpretation |
| --- | --- |
| 0 | Current strict criterion |
"""
        val expected = """Use one tolerance:

${'$'}${'$'}
\varepsilon=\eta B,
${'$'}${'$'}

where ${'$'}${'$'}B${'$'}${'$'} is the best continuation.

| ${'$'}${'$'}\eta${'$'}${'$'} | Interpretation |
| --- | --- |
| 0 | Current strict criterion |
"""
        assertEquals(expected, normalizeLatexMarkdown(source))
    }

    @Test fun placesCompactDisplayMathOnSeparateLines() {
        assertEquals("Before\n$$\nx^2\n$$\nafter", normalizeLatexMarkdown("""Before\[x^2\]after"""))
        assertEquals("$$\nx^2\n$$", normalizeLatexMarkdown("""\[x^2\]"""))
    }

    @Test fun preservesExistingDollarMathAndMixedForms() {
        assertEquals(
            """${'$'}${'$'}x${'$'}${'$'}, ${'$'}${'$'}y${'$'}${'$'}, ${'$'}${'$'}z${'$'}${'$'}

${'$'}${'$'}
a+b
${'$'}${'$'}""",
            normalizeLatexMarkdown("""${'$'}x${'$'}, ${'$'}${'$'}y${'$'}${'$'}, \(z\)

${'$'}${'$'}
a+b
${'$'}${'$'}"""),
        )
    }

    @Test fun leavesBackticksAndBothFenceStylesUntouched() {
        val source = """`\(x\)` and ``\[x\]``
```latex
\(x\) and \[y\] and ${'$'}z${'$'}
```
~~~text
\[
x^2
\]
~~~
"""
        assertEquals(source, normalizeLatexMarkdown(source))
    }

    @Test fun respectsEscapedOpeningAndClosingDelimiters() {
        val escaped = """\\(x\\) and \\[x\\] and \${'$'}x\${'$'}"""
        assertEquals(escaped, normalizeLatexMarkdown(escaped))
        assertEquals("""${'$'}${'$'}a\\)b${'$'}${'$'}""", normalizeLatexMarkdown("""\(a\\)b\)"""))
    }

    @Test fun doesNotTurnPricesIntoEquations() {
        for (source in listOf("Cost ${'$'}5 and ${'$'}10", "Range ${'$'}5–${'$'}10", "Escaped \\${'$'}5.00")) {
            assertEquals(source, normalizeLatexMarkdown(source))
        }
        assertEquals("$$2+2$$", normalizeLatexMarkdown("${'$'}2+2${'$'}"))
    }

    @Test fun waitsForCompleteBackslashDelimitersDuringStreaming() {
        for (source in listOf("""\(\eta +""", """\[\frac{a}{b}""", """\[x\""", """\(x\\)""")) {
            assertEquals(source, normalizeLatexMarkdown(source))
        }
        assertEquals("$$\\eta$$", normalizeLatexMarkdown("""\(\eta\)"""))
    }

    @Test fun normalizesMathThroughThe256KiCharacterBoundary() {
        val math = """\(x\) """
        for (length in listOf(32 * 1024 + 1, 256 * 1024 - 1, 256 * 1024)) {
            val padding = "a".repeat(length - math.length)
            assertEquals("${'$'}${'$'}x${'$'}${'$'} " + padding, normalizeLatexMarkdown(math + padding))
        }
        val oversized = math + "a".repeat(256 * 1024 + 1 - math.length)
        assertEquals(oversized, normalizeLatexMarkdown(oversized))
    }

    @Test fun preservesEmptyDelimitersAndLongPlainTextFallback() {
        for (source in listOf("""\(\)""", """\[  \]""", """\(x\) """ + "a".repeat(256 * 1024))) {
            assertEquals(source, normalizeLatexMarkdown(source))
        }
    }
}
