package com.example.ginsengidentifier.ui.theme

import androidx.compose.ui.graphics.Color

// ── 品牌色 ────────────────────────────────────────────────────────────────
// 玉（Jade）：主色，来自人参皂苷的冷绿
val Jade700 = Color(0xFF146B52)
val Jade900 = Color(0xFF003829)
val Jade300 = Color(0xFF8CD5B8)
val Jade100 = Color(0xFFA8F0D6)
val Jade50 = Color(0xFFDDF6EB)

// 奶油（Cream）：背景，像药材纸包
val Cream = Color(0xFFF7F5EF)
val CreamDark = Color(0xFF0F1512)

// 人参果红：强调色 / 人参类
val Berry = Color(0xFF9C3F52)
val BerryLight = Color(0xFFFFB2BE)
val BerryContainer = Color(0xFFFFD9DE)

// 三类标签色（图表 / 卡片 / 小组件共用）
val ClassRenshen = Color(0xFFD4575F)   // 人参
val ClassXiyangshen = Color(0xFF2E8B6E) // 西洋参
val ClassJiegeng = Color(0xFF7C6BD6)    // 桔梗
val ClassUnknown = Color(0xFFC98A2E)    // 低置信

// ── Light scheme ─────────────────────────────────────────────────────────
val LightPrimary = Jade700
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Jade100
val LightOnPrimaryContainer = Jade900
val LightSecondary = Color(0xFF4E6357)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD1E8DA)
val LightOnSecondaryContainer = Color(0xFF0B1F16)
val LightTertiary = Berry
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = BerryContainer
val LightOnTertiaryContainer = Color(0xFF3F0016)
val LightError = Color(0xFFB3261E)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFF9DEDC)
val LightOnErrorContainer = Color(0xFF410E0B)
val LightBackground = Cream
val LightOnBackground = Color(0xFF191C19)
val LightSurface = Color(0xFFFCFAF5)
val LightOnSurface = Color(0xFF191C19)
val LightSurfaceVariant = Color(0xFFDCE5DE)
val LightOnSurfaceVariant = Color(0xFF404943)
val LightOutline = Color(0xFF707973)
val LightOutlineVariant = Color(0xFFC0C9C2)

// ── Dark scheme ──────────────────────────────────────────────────────────
val DarkPrimary = Jade300
val DarkOnPrimary = Jade900
val DarkPrimaryContainer = Color(0xFF00513C)
val DarkOnPrimaryContainer = Jade100
val DarkSecondary = Color(0xFFB5CCC0)
val DarkOnSecondary = Color(0xFF21352B)
val DarkSecondaryContainer = Color(0xFF374B41)
val DarkOnSecondaryContainer = Color(0xFFD1E8DA)
val DarkTertiary = BerryLight
val DarkOnTertiary = Color(0xFF5F1127)
val DarkTertiaryContainer = Color(0xFF7D293C)
val DarkOnTertiaryContainer = BerryContainer
val DarkError = Color(0xFFF2B8B5)
val DarkOnError = Color(0xFF601410)
val DarkErrorContainer = Color(0xFF8C1D18)
val DarkOnErrorContainer = Color(0xFFF9DEDC)
val DarkBackground = CreamDark
val DarkOnBackground = Color(0xFFE1E3DE)
val DarkSurface = Color(0xFF111815)
val DarkOnSurface = Color(0xFFE1E3DE)
val DarkSurfaceVariant = Color(0xFF404943)
val DarkOnSurfaceVariant = Color(0xFFC0C9C2)
val DarkOutline = Color(0xFF8A938C)
val DarkOutlineVariant = Color(0xFF404943)

fun classColor(label: String): Color = when (label) {
    "renshen" -> ClassRenshen
    "xiyangshen" -> ClassXiyangshen
    "jiegeng" -> ClassJiegeng
    else -> ClassUnknown
}
