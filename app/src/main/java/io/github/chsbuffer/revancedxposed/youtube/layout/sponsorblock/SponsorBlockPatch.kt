package io.github.chsbuffer.revancedxposed.youtube.layout.sponsorblock

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.view.ViewGroup
import app.revanced.extension.shared.Utils
import app.revanced.extension.youtube.sponsorblock.SegmentPlaybackController
import app.revanced.extension.youtube.sponsorblock.ui.CreateSegmentButton
import app.revanced.extension.youtube.sponsorblock.ui.SponsorBlockAboutPreference
import app.revanced.extension.youtube.sponsorblock.ui.SponsorBlockPreferenceGroup
import app.revanced.extension.youtube.sponsorblock.ui.SponsorBlockStatsPreferenceCategory
import app.revanced.extension.youtube.sponsorblock.ui.SponsorBlockViewController
import app.revanced.extension.youtube.sponsorblock.ui.VotingButton
import io.github.chsbuffer.revancedxposed.R
import io.github.chsbuffer.revancedxposed.patch
import io.github.chsbuffer.revancedxposed.scopedHook
import io.github.chsbuffer.revancedxposed.setObjectField
import io.github.chsbuffer.revancedxposed.shared.misc.settings.preference.NonInteractivePreference
import io.github.chsbuffer.revancedxposed.shared.misc.settings.preference.PreferenceCategory
import io.github.chsbuffer.revancedxposed.shared.misc.settings.preference.PreferenceScreenPreference
import io.github.chsbuffer.revancedxposed.youtube.misc.playercontrols.ControlInitializer
import io.github.chsbuffer.revancedxposed.youtube.misc.playercontrols.PlayerControls
import io.github.chsbuffer.revancedxposed.youtube.misc.playercontrols.addTopControl
import io.github.chsbuffer.revancedxposed.youtube.misc.playercontrols.initializeTopControl
import io.github.chsbuffer.revancedxposed.youtube.misc.playertype.PlayerTypeHook
import io.github.chsbuffer.revancedxposed.youtube.misc.settings.PreferenceScreen
import io.github.chsbuffer.revancedxposed.youtube.video.information.VideoInformationPatch
import io.github.chsbuffer.revancedxposed.youtube.video.information.onCreateHook
import io.github.chsbuffer.revancedxposed.youtube.video.information.videoTimeHooks
import io.github.chsbuffer.revancedxposed.youtube.video.videoid.VideoId
import io.github.chsbuffer.revancedxposed.youtube.video.videoid.videoIdHooks
import org.luckypray.dexkit.wrap.DexMethod

val SponsorBlock = patch(
    name = "SponsorBlock",
    description = "Adds options to enable and configure SponsorBlock, which can skip undesired video segments such as sponsored content."
) {
    dependsOn(
        VideoInformationPatch,
        VideoId,
        PlayerTypeHook,
        PlayerControls,
    )

    // -----------------------------
    // Settings screen integration
    // -----------------------------
    PreferenceScreen.SPONSORBLOCK.addPreferences(
        PreferenceCategory(
            key = "revanced_settings_screen_10_sponsorblock",
            sorting = PreferenceScreenPreference.Sorting.UNSORTED,
            preferences = emptySet(),
            tag = SponsorBlockPreferenceGroup::class.java
        ),
        PreferenceCategory(
            key = "revanced_sb_stats",
            sorting = PreferenceScreenPreference.Sorting.UNSORTED,
            preferences = emptySet(),
            tag = SponsorBlockStatsPreferenceCategory::class.java
        ),
        PreferenceCategory(
            key = "revanced_sb_about",
            sorting = PreferenceScreenPreference.Sorting.UNSORTED,
            preferences = setOf(
                NonInteractivePreference(
                    key = "revanced_sb_about_api",
                    tag = SponsorBlockAboutPreference::class.java,
                    selectable = true,
                )
            )
        )
    )

    // -----------------------------
    // Player controls
    // -----------------------------
    addTopControl(R.layout.revanced_sb_button)

    videoTimeHooks.add {
        runCatching { SegmentPlaybackController.setVideoTime(it) }
    }

    videoIdHooks.add {
        runCatching { SegmentPlaybackController.setCurrentVideoId(it) }
    }

    // -----------------------------
    // Seekbar drawing
    // -----------------------------
    var rectSetOnce = false
    val sponsorBarRectField = ::SponsorBarRect.field

    ::seekbarOnDrawFingerprint.hookMethod {
        before { param ->
            rectSetOnce = false
            val rect = sponsorBarRectField.get(param.thisObject) as? Rect ?: return@before
            runCatching { SegmentPlaybackController.setSponsorBarRect(rect) }
        }
    }

    val drawCircleMethod =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            "Landroid/view/DisplayListCanvas;->drawCircle(FFFLandroid/graphics/Paint;)V"
        else
            "Landroid/graphics/RecordingCanvas;->drawCircle(FFFLandroid/graphics/Paint;)V"

    ::seekbarOnDrawFingerprint.hookMethod(
        scopedHook(
            DexMethod("Landroid/graphics/Rect;->set(IIII)V").toMethod() to {
                after { param ->
                    if (rectSetOnce) return@after
                    val rect = param.thisObject as? Rect ?: return@after
                    runCatching { SegmentPlaybackController.setSponsorBarThickness(rect.height()) }
                    rectSetOnce = true
                }
            },
            DexMethod(drawCircleMethod).toMethod() to {
                before { param ->
                    val canvas = param.thisObject as? Canvas ?: return@before
                    val y = param.args.getOrNull(1) as? Float ?: return@before
                    runCatching { SegmentPlaybackController.drawSponsorTimeBars(canvas, y) }
                }
            }
        )
    )

    // -----------------------------
    // Control visibility
    // -----------------------------
    initializeTopControl(
        ControlInitializer(
            R.id.revanced_sb_create_segment_button,
            CreateSegmentButton::initialize,
            CreateSegmentButton::setVisibility,
            CreateSegmentButton::setVisibilityImmediate,
            CreateSegmentButton::setVisibilityNegatedImmediate
        )
    )

    initializeTopControl(
        ControlInitializer(
            R.id.revanced_sb_voting_button,
            VotingButton::initialize,
            VotingButton::setVisibility,
            VotingButton::setVisibilityImmediate,
            VotingButton::setVisibilityNegatedImmediate
        )
    )

    // -----------------------------
    // Append time without segments
    // -----------------------------
    ::appendTimeFingerprint.hookMethod {
        before { param ->
            val time = param.args[2]?.toString() ?: return@before
            param.args[2] = runCatching {
                SegmentPlaybackController.appendTimeWithoutSegments(time)
            }.getOrDefault(time)
        }
    }

    // -----------------------------
    // Initialize controller
    // -----------------------------
    onCreateHook.add {
        runCatching { SegmentPlaybackController.initialize(it) }
    }

    // -----------------------------
    // Overlay initialization
    // -----------------------------
    val controlsOverlayLayout =
        Utils.getResourceIdentifier("size_adjustable_youtube_controls_overlay", "layout")

    ::controlsOverlayFingerprint.hookMethod(
        scopedHook(
            DexMethod("Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;)Landroid/view/View;").toMember()
        ) {
            val insetOverlayViewLayout = inset_overlay_view_layout
            after { param ->
                if (param.args[0] != controlsOverlayLayout) return@after
                val layout = param.result as? ViewGroup ?: return@after
                val overlay = layout.findViewById<ViewGroup>(insetOverlayViewLayout) ?: return@after
                runCatching { SponsorBlockViewController.initialize(overlay) }
            }
        }
    )

    // -----------------------------
    // Ad progress visibility
    // -----------------------------
    ::adProgressTextViewVisibilityFingerprint.hookMethod(
        scopedHook(::AdProgressTextVisibility.method) {
            before { param ->
                val visibility = param.args.getOrNull(0) as? Int ?: return@before
                runCatching { SegmentPlaybackController.setAdProgressTextVisibility(visibility) }
            }
        }
    )

    // -----------------------------
    // ClassLoader injection
    // -----------------------------
    fun injectClassLoader(self: ClassLoader, host: ClassLoader) {
        runCatching {
            host.setObjectField("parent", object : ClassLoader(host.parent) {
                override fun findClass(name: String): Class<*> {
                    if (name.startsWith("app.revanced")) {
                        return self.loadClass(name)
                    }
                    throw ClassNotFoundException(name)
                }
            })
        }
    }

    injectClassLoader(this::class.java.classLoader!!, classLoader)
}
