package com.norypt.haven.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Norypt brand tokens. Values are verified against norypt.com's stylesheet (see brand/BRAND.md).
 * Supporting neutrals are derived for contrast (WCAG AA for body text in both themes).
 */
object NoryptColors {
    val Navy = Color(0xFF0B132B)        // --color-navy
    val NavyLight = Color(0xFF1A2540)   // --color-navy-light
    val NavyDeep = Color(0xFF080F1E)    // dark section background on the site
    val Blue = Color(0xFF3A86FF)        // --color-eblue
    val BlueDark = Color(0xFF1A6AFF)    // --color-eblue-dark
    val BlueLight = Color(0xFF6BA8FF)   // --color-eblue-light
    val Cloud = Color(0xFFF5F7FA)       // site body background
    val White = Color(0xFFFFFFFF)

    // Derived neutrals (not brand colours; chosen for readability)
    val Slate700 = Color(0xFF334155)
    val Slate500 = Color(0xFF64748B)
    val Slate300 = Color(0xFFCBD5E1)
    val Slate200 = Color(0xFFE2E8F0)
    val Slate100 = Color(0xFFF1F5F9)
    val DarkText = Color(0xFFE6EAF2)
    val DarkTextMuted = Color(0xFFA9B4C7)
    val DarkOutline = Color(0xFF33415C)

    // Semantic (also carried by icons/text, never colour alone)
    val Success = Color(0xFF1E8E5A)
    val SuccessDark = Color(0xFF5AD79A)
    val Warning = Color(0xFFB7791F)
    val WarningDark = Color(0xFFF2B95C)
    val Danger = Color(0xFFC0392B)
    val DangerDark = Color(0xFFFF7B6E)
}
