package dev.readflow.core.model

/**
 * Single source of truth for reading-surface colours across all render engines (EPUB / TXT / PDF / MD).
 *
 * Colours are ARGB [Int]s (0xFFrrggbb) so this stays a pure-Kotlin module with no `android.graphics`
 * dependency — engines pass [paper] / [ink] straight to `setBackgroundColor` / `setTextColor`.
 *
 * The preset values are ported from 静读天下 (Moon+ Reader)'s built-in flat-colour themes (see the
 * external-benchmark audit). Each engine previously hard-coded its own copy of these triples in a
 * per-engine `paletteFor`; they now all delegate to [readerPaletteFor] so a colour change lands in
 * one place and every format stays consistent.
 */
data class ReaderPalette(
    /** Page background. */
    val paper: Int,
    /** Body-text / ink colour. */
    val ink: Int,
    /** True for dark presets — engines pick the dark code-block background and dark UI accents. */
    val isNight: Boolean,
)

/**
 * Resolves the [ReaderPalette] for a [ThemeMode]. [SYSTEM] follows [systemNight]; every other mode is
 * an explicit flat preset whose colours are fixed (independent of the OS setting).
 *
 * Moon+ source triples (bg / text):
 *  - LIGHT  日间     #F7F5EF / #202522  (warm editorial paper)
 *  - WHITE  纯白     #FAFAF8 / #1A1A1A  (clean off-white; Moon+ pure-white #FFFFFF softened per WCAG)
 *  - SEPIA  护眼黄   #F5F0E8 / #5B4636  (parchment; Moon+ Day2 sepia family)
 *  - GREEN  护眼绿   #DBECE1 / #34433A  (Moon+ pro2 mint — eye-care green)
 *  - GREY   灰      #DBD7D1 / #2A2620  (Moon+ Day3 light grey paper)
 *  - DARK   夜间     #171B18 / #E5E9E4  (soft evergreen black, never pure black)
 *  - SLATE  深灰     #232323 / #CCCCCC  (Moon+ Night Theme)
 *  - NAVY   深蓝     #0E141E / #BDC9C8  (Moon+ pro3 deep navy)
 *  - BLACK  纯黑     #101310 / #E8E6E1  (legacy label; near-black without harsh pure black)
 */
fun readerPaletteFor(mode: ThemeMode, systemNight: Boolean): ReaderPalette = when (mode) {
    ThemeMode.SYSTEM -> if (systemNight) DARK_PALETTE else LIGHT_PALETTE
    ThemeMode.LIGHT -> LIGHT_PALETTE
    ThemeMode.WHITE -> ReaderPalette(0xFFFAFAF8.toInt(), 0xFF1A1A1A.toInt(), isNight = false)
    ThemeMode.SEPIA -> ReaderPalette(0xFFF5F0E8.toInt(), 0xFF5B4636.toInt(), isNight = false)
    ThemeMode.GREEN -> ReaderPalette(0xFFDBECE1.toInt(), 0xFF34433A.toInt(), isNight = false)
    ThemeMode.GREY -> ReaderPalette(0xFFDBD7D1.toInt(), 0xFF2A2620.toInt(), isNight = false)
    ThemeMode.DARK -> DARK_PALETTE
    ThemeMode.SLATE -> ReaderPalette(0xFF232323.toInt(), 0xFFCCCCCC.toInt(), isNight = true)
    ThemeMode.NAVY -> ReaderPalette(0xFF0E141E.toInt(), 0xFFBDC9C8.toInt(), isNight = true)
    ThemeMode.BLACK -> ReaderPalette(0xFF101310.toInt(), 0xFFE8E6E1.toInt(), isNight = true)
}

/** Chinese picker label for each preset (used by reader + settings theme choosers). */
fun ThemeMode.readerThemeLabel(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "日间"
    ThemeMode.WHITE -> "纯白"
    ThemeMode.SEPIA -> "护眼黄"
    ThemeMode.GREEN -> "护眼绿"
    ThemeMode.GREY -> "灰白"
    ThemeMode.DARK -> "夜间"
    ThemeMode.SLATE -> "深灰"
    ThemeMode.NAVY -> "深蓝"
    ThemeMode.BLACK -> "纯黑"
}

private val LIGHT_PALETTE = ReaderPalette(0xFFF7F5EF.toInt(), 0xFF202522.toInt(), isNight = false)
private val DARK_PALETTE = ReaderPalette(0xFF171B18.toInt(), 0xFFE5E9E4.toInt(), isNight = true)
