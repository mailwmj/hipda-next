/**
 * Stateless forum emoji mapping for all 61 HiPDA forum smilies (21 Default, 16 CoolMonkey,
 * 24 GrapeMan) with light/dark drawable resolution and picker group ordering.
 *
 * All 61 `(code, imageSrc, lightRes, darkRes)` tuples and 3 category groups are locked by
 * `hipda/src/test/java/net/jejer/hipda/emoji/EmojiMapTest.kt`.
 */
package net.jejer.hipda.emoji

import androidx.annotation.DrawableRes
import com.vanniktech.emoji.R
import java.util.Collections

/**
 * A single category tab in the forum emoji picker.
 *
 * @property name Display name of the group (e.g. "默认", "酷猴", "呆呆").
 * @property iconCode Forum emoji code of the representative icon (the first code in [codes]).
 * @property codes Ordered list of forum emoji codes belonging to this group.
 */
data class EmojiGroup(
    val name: String,
    val iconCode: String,
    val codes: List<String>,
)

/**
 * Stateless lookup from HiPDA forum emoji codes (and Discuz smilie image keys) to light/dark
 * drawable resource IDs in `:emoji`.
 */
object EmojiMap {

    private data class EmojiEntry(
        val code: String,
        val imageSrc: String,
        @DrawableRes val lightDrawable: Int,
        @DrawableRes val darkDrawable: Int,
    )

    private val defaultEntries: List<EmojiEntry> = listOf(
        EmojiEntry(":)", "default_smile", R.drawable.default_smile, R.drawable.default_smile),
        EmojiEntry(":sweat:", "default_sweat", R.drawable.default_sweat, R.drawable.default_sweat),
        EmojiEntry(":huffy:", "default_huffy", R.drawable.default_huffy, R.drawable.default_huffy),
        EmojiEntry(":cry:", "default_cry", R.drawable.default_cry, R.drawable.default_cry),
        EmojiEntry(":titter:", "default_titter", R.drawable.default_titter, R.drawable.default_titter),
        EmojiEntry(":handshake:", "default_handshake", R.drawable.default_handshake, R.drawable.default_handshake),
        EmojiEntry(":victory:", "default_victory", R.drawable.default_victory, R.drawable.default_victory),
        EmojiEntry(":curse:", "default_curse", R.drawable.default_curse, R.drawable.default_curse),
        EmojiEntry(":dizzy:", "default_dizzy", R.drawable.default_dizzy, R.drawable.default_dizzy),
        EmojiEntry(":shutup:", "default_shutup", R.drawable.default_shutup, R.drawable.default_shutup),
        EmojiEntry(":funk:", "default_funk", R.drawable.default_funk, R.drawable.default_funk),
        EmojiEntry(":loveliness:", "default_loveliness", R.drawable.default_loveliness, R.drawable.default_loveliness),
        EmojiEntry(":(", "default_sad", R.drawable.default_sad, R.drawable.default_sad),
        EmojiEntry(":D", "default_biggrin", R.drawable.default_biggrin, R.drawable.default_biggrin),
        EmojiEntry(":cool:", "default_cool", R.drawable.default_cool, R.drawable.default_cool),
        EmojiEntry(":mad:", "default_mad", R.drawable.default_mad, R.drawable.default_mad),
        EmojiEntry(":o", "default_shocked", R.drawable.default_shocked, R.drawable.default_shocked),
        EmojiEntry(":P", "default_tongue", R.drawable.default_tongue, R.drawable.default_tongue),
        EmojiEntry(":lol:", "default_lol", R.drawable.default_lol, R.drawable.default_lol),
        EmojiEntry(":shy:", "default_shy", R.drawable.default_shy, R.drawable.default_shy),
        EmojiEntry(":sleepy:", "default_sleepy", R.drawable.default_sleepy, R.drawable.default_sleepy),
    )

    private val monkeyEntries: List<EmojiEntry> = listOf(
        EmojiEntry("{:2_41:}", "coolmonkey_01", R.drawable.coolmonkey_01, R.drawable.coolmonkey_01_dark),
        EmojiEntry("{:2_42:}", "coolmonkey_02", R.drawable.coolmonkey_02, R.drawable.coolmonkey_02_dark),
        EmojiEntry("{:2_43:}", "coolmonkey_03", R.drawable.coolmonkey_03, R.drawable.coolmonkey_03_dark),
        EmojiEntry("{:2_44:}", "coolmonkey_04", R.drawable.coolmonkey_04, R.drawable.coolmonkey_04_dark),
        EmojiEntry("{:2_45:}", "coolmonkey_05", R.drawable.coolmonkey_05, R.drawable.coolmonkey_05_dark),
        EmojiEntry("{:2_46:}", "coolmonkey_06", R.drawable.coolmonkey_06, R.drawable.coolmonkey_06_dark),
        EmojiEntry("{:2_47:}", "coolmonkey_07", R.drawable.coolmonkey_07, R.drawable.coolmonkey_07_dark),
        EmojiEntry("{:2_48:}", "coolmonkey_08", R.drawable.coolmonkey_08, R.drawable.coolmonkey_08_dark),
        EmojiEntry("{:2_49:}", "coolmonkey_09", R.drawable.coolmonkey_09, R.drawable.coolmonkey_09_dark),
        EmojiEntry("{:2_50:}", "coolmonkey_10", R.drawable.coolmonkey_10, R.drawable.coolmonkey_10_dark),
        EmojiEntry("{:2_51:}", "coolmonkey_11", R.drawable.coolmonkey_11, R.drawable.coolmonkey_11_dark),
        EmojiEntry("{:2_52:}", "coolmonkey_12", R.drawable.coolmonkey_12, R.drawable.coolmonkey_12_dark),
        EmojiEntry("{:2_53:}", "coolmonkey_13", R.drawable.coolmonkey_13, R.drawable.coolmonkey_13_dark),
        EmojiEntry("{:2_54:}", "coolmonkey_14", R.drawable.coolmonkey_14, R.drawable.coolmonkey_14_dark),
        EmojiEntry("{:2_55:}", "coolmonkey_15", R.drawable.coolmonkey_15, R.drawable.coolmonkey_15_dark),
        EmojiEntry("{:2_56:}", "coolmonkey_16", R.drawable.coolmonkey_16, R.drawable.coolmonkey_16_dark),
    )

    private val dumbEntries: List<EmojiEntry> = listOf(
        EmojiEntry("{:3_57:}", "grapeman_01", R.drawable.grapeman_01, R.drawable.grapeman_01_dark),
        EmojiEntry("{:3_58:}", "grapeman_02", R.drawable.grapeman_02, R.drawable.grapeman_02_dark),
        EmojiEntry("{:3_59:}", "grapeman_03", R.drawable.grapeman_03, R.drawable.grapeman_03_dark),
        EmojiEntry("{:3_60:}", "grapeman_04", R.drawable.grapeman_04, R.drawable.grapeman_04_dark),
        EmojiEntry("{:3_61:}", "grapeman_05", R.drawable.grapeman_05, R.drawable.grapeman_05_dark),
        EmojiEntry("{:3_62:}", "grapeman_06", R.drawable.grapeman_06, R.drawable.grapeman_06_dark),
        EmojiEntry("{:3_63:}", "grapeman_07", R.drawable.grapeman_07, R.drawable.grapeman_07_dark),
        EmojiEntry("{:3_64:}", "grapeman_08", R.drawable.grapeman_08, R.drawable.grapeman_08_dark),
        EmojiEntry("{:3_65:}", "grapeman_09", R.drawable.grapeman_09, R.drawable.grapeman_09_dark),
        EmojiEntry("{:3_66:}", "grapeman_10", R.drawable.grapeman_10, R.drawable.grapeman_10_dark),
        EmojiEntry("{:3_67:}", "grapeman_11", R.drawable.grapeman_11, R.drawable.grapeman_11_dark),
        EmojiEntry("{:3_68:}", "grapeman_12", R.drawable.grapeman_12, R.drawable.grapeman_12_dark),
        EmojiEntry("{:3_69:}", "grapeman_13", R.drawable.grapeman_13, R.drawable.grapeman_13_dark),
        EmojiEntry("{:3_70:}", "grapeman_14", R.drawable.grapeman_14, R.drawable.grapeman_14_dark),
        EmojiEntry("{:3_71:}", "grapeman_15", R.drawable.grapeman_15, R.drawable.grapeman_15_dark),
        EmojiEntry("{:3_72:}", "grapeman_16", R.drawable.grapeman_16, R.drawable.grapeman_16_dark),
        EmojiEntry("{:3_73:}", "grapeman_17", R.drawable.grapeman_17, R.drawable.grapeman_17_dark),
        EmojiEntry("{:3_74:}", "grapeman_18", R.drawable.grapeman_18, R.drawable.grapeman_18_dark),
        EmojiEntry("{:3_75:}", "grapeman_19", R.drawable.grapeman_19, R.drawable.grapeman_19_dark),
        EmojiEntry("{:3_76:}", "grapeman_20", R.drawable.grapeman_20, R.drawable.grapeman_20_dark),
        EmojiEntry("{:3_77:}", "grapeman_21", R.drawable.grapeman_21, R.drawable.grapeman_21_dark),
        EmojiEntry("{:3_78:}", "grapeman_22", R.drawable.grapeman_22, R.drawable.grapeman_22_dark),
        EmojiEntry("{:3_79:}", "grapeman_23", R.drawable.grapeman_23, R.drawable.grapeman_23_dark),
        EmojiEntry("{:3_80:}", "grapeman_24", R.drawable.grapeman_24, R.drawable.grapeman_24_dark),
    )

    private val allEntries: List<EmojiEntry> = defaultEntries + monkeyEntries + dumbEntries

    private val entriesByCode: Map<String, EmojiEntry> = allEntries.associateBy { it.code }

    private val entriesByImageSrc: Map<String, EmojiEntry> = allEntries.associateBy { it.imageSrc }

    /**
     * Emoji categories matching the legacy picker panel order (默认、酷猴、呆呆).
     * Each group's [EmojiGroup.iconCode] is its first emoji code (product-design §7.4).
     */
    val groups: List<EmojiGroup> = Collections.unmodifiableList(
        listOf(
            buildGroup(name = "默认", entries = defaultEntries),
            buildGroup(name = "酷猴", entries = monkeyEntries),
            buildGroup(name = "呆呆", entries = dumbEntries),
        ),
    )

    /**
     * Returns the drawable resource ID for the given forum emoji [code] in light (`dark = false`)
     * or dark (`dark = true`) theme, or `null` if [code] is not a known forum emoji code.
     */
    @DrawableRes
    fun drawableFor(code: String, dark: Boolean): Int? {
        val entry = entriesByCode[code] ?: return null
        return if (dark) entry.darkDrawable else entry.lightDrawable
    }

    /**
     * Returns the drawable resource ID for a Discuz smilie image key [imageSrc]
     * (e.g. `"default_smile"`, `"coolmonkey_01"`, `"grapeman_01"`), matching legacy
     * `EmojiHandler.getDrawableResId(imgSrc)`, or `null` if unknown.
     */
    @DrawableRes
    fun drawableForImageSrc(imageSrc: String, dark: Boolean): Int? {
        val entry = entriesByImageSrc[imageSrc] ?: return null
        return if (dark) entry.darkDrawable else entry.lightDrawable
    }

    /**
     * Returns the canonical forum emoji code (e.g. `":)"`, `"{:2_41:}"`, `"{:3_57:}"`) for a
     * Discuz smilie image key [imageSrc], or `null` if unknown.
     */
    fun codeForImageSrc(imageSrc: String): String? = entriesByImageSrc[imageSrc]?.code

    private fun buildGroup(name: String, entries: List<EmojiEntry>): EmojiGroup = EmojiGroup(
        name = name,
        iconCode = entries.first().code,
        codes = Collections.unmodifiableList(entries.map { it.code }),
    )
}
