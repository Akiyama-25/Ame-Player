package Akari.NCM.player.ui

import Akari.NCM.player.data.ColorVariant
import Akari.NCM.player.data.ContrastLevel
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.ui.component.AdaptiveButtonGroup
import Akari.NCM.player.ui.component.AdaptiveButtonSpec
import Akari.NCM.player.ui.component.ExpressiveButtonStyle
import Akari.NCM.player.ui.theme.AmePlayerTheme
import Akari.NCM.player.ui.theme.buildExpressiveColorScheme
import Akari.NCM.player.ui.theme.buildPlayerExpressivePalette
import Akari.NCM.player.ui.theme.colorToHsl
import Akari.NCM.player.ui.theme.contrastRatio
import Akari.NCM.player.ui.theme.extractSeedColorFromBitmap
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpressiveThemeAndContrastTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ThemePrefs.init(context)
    }

    @Test
    fun `useMaterialBackground false uses pure black in dark mode and pure white in light mode`() {
        val seed = Color(0xFF4CAF50)
        for (variant in ColorVariant.entries) {
            for (contrast in ContrastLevel.entries) {
                val darkScheme = buildExpressiveColorScheme(
                    seedColor = seed,
                    darkTheme = true,
                    variant = variant,
                    contrastLevel = contrast,
                    useMaterialBackground = false
                )
                assertEquals(Color.Black, darkScheme.background)
                assertEquals(Color.Black, darkScheme.surface)
                assertTrue(
                    "Primary on pure black must meet contrast ratio",
                    contrastRatio(darkScheme.primary, darkScheme.background) >= 4.45f
                )

                val lightScheme = buildExpressiveColorScheme(
                    seedColor = seed,
                    darkTheme = false,
                    variant = variant,
                    contrastLevel = contrast,
                    useMaterialBackground = false
                )
                assertEquals(Color.White, lightScheme.background)
                assertEquals(Color.White, lightScheme.surface)
                assertTrue(
                    "Primary on pure white must meet contrast ratio",
                    contrastRatio(lightScheme.primary, lightScheme.background) >= 4.45f
                )
            }
        }
    }

    @Test
    fun `player expressive palette guarantees contrast for buttons and strict played or unplayed lyric colors`() {
        val testSeeds = listOf(
            Color(0xFFE91E63),
            Color(0xFF2196F3),
            Color(0xFFFFEB3B),
            Color(0xFF1B5E20),
            Color(0xFFFAFAFA),
            Color(0xFF121212)
        )
        for (seed in testSeeds) {
            for (blurCover in listOf(true, false)) {
                for (useMaterialBg in listOf(true, false)) {
                    for (darkTheme in listOf(true, false)) {
                        for (contrast in ContrastLevel.entries) {
                            val palette = buildPlayerExpressivePalette(
                                coverSeedColor = seed,
                                coverAverageColor = seed,
                                darkTheme = darkTheme,
                                variant = ColorVariant.EXPRESSIVE,
                                contrastLevel = contrast,
                                blurCoverBackground = blurCover,
                                useMaterialBackground = useMaterialBg
                            )

                            // Unplayed lyrics must strictly be pure Black or pure White
                            assertTrue(
                                "Unplayed lyric color must be pure Black or pure White",
                                palette.unplayedLyricColor == Color.Black || palette.unplayedLyricColor == Color.White
                            )

                            // Played lyrics must have sufficient contrast against effective background
                            val playedContrast = contrastRatio(
                                palette.playedLyricColor,
                                palette.effectiveBackgroundColor
                            )
                            assertTrue(
                                "Played lyric contrast ($playedContrast) must be >= 4.45",
                                playedContrast >= 4.45f
                            )

                            // Button text/icon vs button container contrast
                            val primaryBtnInnerContrast = contrastRatio(
                                palette.onPrimaryButton,
                                palette.primaryButtonContainer
                            )
                            assertTrue(
                                "Primary button inner contrast ($primaryBtnInnerContrast) must be >= 4.45",
                                primaryBtnInnerContrast >= 4.45f
                            )

                            val secondaryBtnInnerContrast = contrastRatio(
                                palette.onSecondaryButton,
                                palette.secondaryButtonContainer
                            )
                            assertTrue(
                                "Secondary button inner contrast ($secondaryBtnInnerContrast) must be >= 4.45",
                                secondaryBtnInnerContrast >= 4.45f
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `extractSeedColorFromBitmap extracts vibrant color from album cover`() {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color(0xFFD32F2F).toArgb())
        val extracted = extractSeedColorFromBitmap(bmp, Color(0xFF2196F3))
        val hsl = colorToHsl(extracted)
        assertTrue("Extracted hue (${hsl.h}) should be red", hsl.h <= 25f || hsl.h >= 335f)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `AdaptiveButtonGroup stacks buttons vertically on portrait tall screens`() {
        composeTestRule.setContent {
            AmePlayerTheme {
                AdaptiveButtonGroup(
                    buttons = listOf(
                        AdaptiveButtonSpec(
                            text = "确认保存设置",
                            style = ExpressiveButtonStyle.FILLED,
                            onClick = {}
                        ),
                        AdaptiveButtonSpec(
                            text = "取消返回",
                            style = ExpressiveButtonStyle.TEXT,
                            onClick = {}
                        )
                    )
                )
            }
        }

        val firstBounds = composeTestRule.onNodeWithText("确认保存设置").fetchSemanticsNode().boundsInRoot
        val secondBounds = composeTestRule.onNodeWithText("取消返回").fetchSemanticsNode().boundsInRoot

        // In portrait tall screen, first button is stacked above second button
        assertTrue(
            "Buttons should be stacked vertically in portrait (first.top=${firstBounds.top}, second.top=${secondBounds.top})",
            secondBounds.top >= firstBounds.bottom - 1f
        )
    }
}
