package com.hazyoctophant.cardvault.model

/**
 * A color theme. Colors are ARGB longs so this file stays free of Android/Compose types.
 * [accent]/[secondary] are the light tones used on dark backgrounds; the *Deep variants are used in light mode.
 */
enum class ThemePreset(
    val label: String,
    val description: String,
    val accent: Long,
    val accentDeep: Long,
    val secondary: Long,
    val secondaryDeep: Long,
    val tertiary: Long,
    val darkBackground: Long,
    val lightBackground: Long,
) {
    MATERIAL_YOU("Material You", "Colors from your phone's wallpaper", 0, 0, 0, 0, 0, 0, 0),
    VAULT("Card Vault", "Sapphire and gold", 0xFF8AB4FF, 0xFF2F5BD3, 0xFFF2C14E, 0xFF8A6A00, 0xFFE5484D, 0xFF14121C, 0xFFF8F7FC),
    PLANESWALKER("Planeswalker", "Arcane purple and spark orange", 0xFFFFB066, 0xFFB3541E, 0xFFC9A7FF, 0xFF6A3FB5, 0xFF7FDBCA, 0xFF16121F, 0xFFFBF6F0),
    ELECTRIC("Electric", "Thunder yellow and sky blue", 0xFFFFD54F, 0xFF8A6800, 0xFF7FB2FF, 0xFF2A5DB0, 0xFFFF7A6B, 0xFF101826, 0xFFFFFBEA),
    GRAND_LINE("Grand Line", "Straw-hat red on an ocean night", 0xFFFF8A80, 0xFFC62828, 0xFF80D8FF, 0xFF0277BD, 0xFFFFE082, 0xFF0D1B2A, 0xFFFFF8E7),
    HOLO_FOIL("Holo Foil", "Iridescent magenta and cyan", 0xFFFF80F4, 0xFFA0179A, 0xFF7DF9FF, 0xFF00838F, 0xFFFFF59D, 0xFF0B0B14, 0xFFF7F4FF),
    PLAINS("Plains", "White mana: ivory and sunlight", 0xFFF3E5AB, 0xFF8C6D1F, 0xFFBFD7EA, 0xFF3D6A8A, 0xFFFFCC80, 0xFF1C1A14, 0xFFFFFDF5),
    ISLAND("Island", "Blue mana: deep sea", 0xFF8CC8FF, 0xFF1565C0, 0xFF80CBC4, 0xFF00796B, 0xFFB39DDB, 0xFF0A1622, 0xFFF3F8FF),
    SWAMP("Swamp", "Black mana: violet shadows", 0xFFCDB6FF, 0xFF5E35B1, 0xFFB0A8A8, 0xFF5D5656, 0xFF9CCC65, 0xFF121014, 0xFFF7F4F8),
    MOUNTAIN("Mountain", "Red mana: lava and ember", 0xFFFF9E80, 0xFFD84315, 0xFFFFCA28, 0xFF9A6A00, 0xFFFF8A80, 0xFF1E100C, 0xFFFFF5F0),
    FOREST("Forest", "Green mana: moss and bark", 0xFFA5D6A7, 0xFF2E7D32, 0xFFD7B98E, 0xFF7A5528, 0xFFFFE082, 0xFF0E1A10, 0xFFF4FAF2),
    RETRO("Retro Handheld", "Four shades of pocket-console green", 0xFF9BBC0F, 0xFF306230, 0xFF8BAC0F, 0xFF306230, 0xFFC4CFA1, 0xFF0F380F, 0xFFE0F0C0),
    MIDNIGHT("Midnight", "Pure black, easy on an OLED screen", 0xFFB0BEC5, 0xFF37474F, 0xFF90A4AE, 0xFF455A64, 0xFFFFD180, 0xFF000000, 0xFFF5F7F8),
}

enum class DarkMode(val label: String) { SYSTEM("Follow phone"), LIGHT("Light"), DARK("Dark") }

/** What is drawn behind the app's screens. */
enum class BackgroundSource(val label: String) {
    NONE("Plain"),
    MY_CARDS("My best cards"),
    PHOTOS("My photos"),
    ONE_CARD("One card"),
}

enum class BackgroundLayout(val label: String) { COLLAGE("Collage"), SINGLE("Single image") }

/** Accent colors offered as overrides on top of any theme. */
val accentSwatches: List<Long> = listOf(
    0xFFFF8A80, 0xFFFFB066, 0xFFFFD54F, 0xFFA5D6A7, 0xFF80CBC4, 0xFF80D8FF,
    0xFF8AB4FF, 0xFFB39DDB, 0xFFFF80F4, 0xFFF48FB1, 0xFFE0E0E0, 0xFFD7B98E,
)
