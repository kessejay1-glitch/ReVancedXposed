package io.github.chsbuffer.revancedxposed.youtube.layout.autocaptions

import app.revanced.extension.youtube.patches.DisableAutoCaptionsPatch
import io.github.chsbuffer.revancedxposed.patch
import io.github.chsbuffer.revancedxposed.shared.misc.settings.preference.SwitchPreference
import io.github.chsbuffer.revancedxposed.youtube.misc.settings.PreferenceScreen

/**
 * AutoCaptionsPatch
 *
 * Polished and defensive version of the original patch:
 *  - Null and exception safe
 *  - Clear grouping and comments
 *  - No behavior change stuff
 */
val AutoCaptionsPatch = patch(
    name = "Disable auto captions",
    description = "Adds an option to disable captions from being automatically enabled.",
) {
    // -----------------------------
    // Settings integration
    // -----------------------------
    runCatching {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("revanced_disable_auto_captions"),
        )
    }

    // -----------------------------
    // Hook subtitle track decision
    // -----------------------------
    ::subtitleTrackFingerprint.hookMethod {
        before { param ->
            runCatching {
                if (DisableAutoCaptionsPatch.disableAutoCaptions()) {
                    param.result = true
                }
            }
        }
    }

    // -----------------------------
    // Hook other fingerprints that influence captions button status
    // -----------------------------
    val fingerprintMap = mapOf(
        ::startVideoInformerFingerprint to 0,
        ::storyboardRendererDecoderRecommendedLevelFingerprint to 1
    )

    fingerprintMap.forEach { (fingerprint, enabled) ->
        fingerprint.hookMethod {
            before { param ->
                runCatching {
                    // Convert the configured enabled flag into a boolean and set status.
                    DisableAutoCaptionsPatch.setCaptionsButtonStatus(enabled != 0)
                }
            }
        }
    }
}
