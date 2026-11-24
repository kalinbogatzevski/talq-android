package talq.core.folders

// The few pieces of the folder rules that common Kotlin cannot do by itself. Each one is the
// platform's own implementation (androidMain: the system's ICU; jvmMain: the JDK), so the phone and
// the offline JVM checks run the same common code around them.
//
// What is deliberately NOT here: which characters a folder name loses (controls, format characters,
// surrogates, private use, noncharacters, blank fillers) and which ones count as whitespace. Those
// are fixed tables in FolderModel.kt, because the phone's Unicode tables can be years older than
// TalQ for desktop's (Android 8 ships Unicode 9) and a name the two clean differently makes desktop
// treat the shared folders file as damaged and stop syncing.

/** Lowercase hex SHA-1 of [bytes] (40 characters). */
internal expect fun sha1Hex(bytes: ByteArray): String

/** Unicode normalisation form C of [s]. */
internal expect fun nfc(s: String): String

/**
 * The general category of code point [cp], numbered as java.lang.Character numbers them (see the
 * CP_* constants below). Only the mark categories are read, for "does this name have a character
 * that paints by itself", and Cn, for "does this device know every character of a new name".
 */
internal expect fun codePointType(cp: Int): Int

/**
 * Simple case folding of one code point (Unicode CaseFolding.txt, statuses C and S; no Turkic
 * rules, no multi-character folds). Two names are "the same ignoring case" when they have the same
 * number of code points and every pair folds to the same value; this is how TalQ for desktop
 * compares folder names for duplicates.
 */
internal expect fun caseFold(cp: Int): Int

/**
 * A name collator for [languageTag] (a BCP 47 tag such as "en-US"), or for the device's language
 * when it is null: locale-aware, case-insensitive (accents still count), digit runs compared by
 * value ("Folder 2" before "Folder 10"). Make one per operation: an instance is not safe to share
 * between threads.
 */
expect fun platformNameCollator(languageTag: String?): NameCollator

internal const val CP_UNASSIGNED = 0
internal const val CP_NON_SPACING_MARK = 6
internal const val CP_ENCLOSING_MARK = 7
internal const val CP_COMBINING_SPACING_MARK = 8
