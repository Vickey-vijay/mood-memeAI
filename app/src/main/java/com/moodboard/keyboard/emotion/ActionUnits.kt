package com.moodboard.keyboard.emotion

import kotlin.math.abs

/**
 * The 26-dimensional FACS-derived Action Unit feature vector (see SPEC_V2 A.3).
 * Every axis is a named, comparable facial-muscle action projected from the 52
 * MediaPipe/ARKit blendshape weights, after per-user baseline correction
 * (see [NeutralBaseline]). Indices 21-23 are asymmetry axes computed directly
 * from the raw left/right corrected deltas, *before* any L/R averaging.
 */
object ActionUnits {

    const val COUNT = 26

    const val AU1_INNER_BROW_RAISER = 0
    const val AU2_OUTER_BROW_RAISER = 1
    const val AU4_BROW_LOWERER = 2
    const val AU5_UPPER_LID_RAISER = 3
    const val AU6_CHEEK_RAISER = 4
    const val AU7_LID_TIGHTENER = 5
    const val AU9_NOSE_WRINKLER = 6
    const val AU10_UPPER_LIP_RAISER = 7
    const val AU12_LIP_CORNER_PULLER = 8
    const val AU14_DIMPLER = 9
    const val AU15_LIP_CORNER_DEPRESSOR = 10
    const val AU16_LOWER_LIP_DEPRESSOR = 11
    const val AU17_CHIN_RAISER = 12
    const val AU18_LIP_PUCKER = 13
    const val AU20_LIP_STRETCHER = 14
    const val AU22_LIP_FUNNELER = 15
    const val AU24_LIP_PRESSOR = 16
    const val AU26_JAW_DROP = 17
    const val AU28_LIP_SUCK = 18
    const val AU33_CHEEK_BLOW = 19
    const val AU43_EYES_CLOSED = 20
    const val ASYM_SMILE = 21
    const val ASYM_BROW = 22
    const val ASYM_BLINK = 23
    const val GAZE_UP = 24
    const val TONGUE = 25

    /** Short AU codes, in index order — used for logs / debugging. */
    val AU_CODES = arrayOf(
        "AU1", "AU2", "AU4", "AU5", "AU6", "AU7", "AU9", "AU10", "AU12", "AU14",
        "AU15", "AU16", "AU17", "AU18", "AU20", "AU22", "AU24", "AU26", "AU28",
        "AU33", "AU43", "ASYM_SMILE", "ASYM_BROW", "ASYM_BLINK", "GAZE_UP", "TONGUE"
    )

    /** Human-readable labels, in index order (see SPEC_V2 A.7), used for the "why" chips and Emotion Lab. */
    val LABELS = arrayOf(
        "Inner brows raised", "Brows raised", "Brows lowered", "Eyes widened",
        "Cheeks raised", "Lids tightened", "Nose wrinkled", "Upper lip raised",
        "Smiling", "Dimpled", "Mouth corners down", "Lower lip down", "Chin raised",
        "Lips puckered", "Lips stretched", "Lips funnelled", "Lips pressed",
        "Jaw open", "Lips sucked in", "Cheeks puffed", "Eyes closed",
        "One-sided smile", "One brow raised", "One eye closed", "Eyes rolled up",
        "Tongue out"
    )

    /** All 52 MediaPipe/ARKit blendshape category names (see SPEC_V2 A.3), used to
     * iterate every raw weight when capturing a [NeutralBaseline]. */
    val BLENDSHAPE_NAMES = arrayOf(
        "_neutral", "browDownLeft", "browDownRight", "browInnerUp",
        "browOuterUpLeft", "browOuterUpRight", "cheekPuff", "cheekSquintLeft",
        "cheekSquintRight", "eyeBlinkLeft", "eyeBlinkRight", "eyeLookDownLeft",
        "eyeLookDownRight", "eyeLookInLeft", "eyeLookInRight", "eyeLookOutLeft",
        "eyeLookOutRight", "eyeLookUpLeft", "eyeLookUpRight", "eyeSquintLeft",
        "eyeSquintRight", "eyeWideLeft", "eyeWideRight", "jawForward", "jawLeft",
        "jawOpen", "jawRight", "mouthClose", "mouthDimpleLeft", "mouthDimpleRight",
        "mouthFrownLeft", "mouthFrownRight", "mouthFunnel", "mouthLeft",
        "mouthLowerDownLeft", "mouthLowerDownRight", "mouthPressLeft",
        "mouthPressRight", "mouthPucker", "mouthRight", "mouthRollLower",
        "mouthRollUpper", "mouthShrugLower", "mouthShrugUpper", "mouthSmileLeft",
        "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
        "mouthUpperUpLeft", "mouthUpperUpRight", "noseSneerLeft", "noseSneerRight",
        "tongueOut"
    )

    /**
     * Projects baseline-corrected blendshape deltas onto the 26-dim AU vector.
     * [c] resolves a blendshape name to its corrected 0..1 delta (0 if unknown).
     */
    fun project(c: (String) -> Float): FloatArray {
        val a = FloatArray(COUNT)
        a[AU1_INNER_BROW_RAISER] = c("browInnerUp")
        a[AU2_OUTER_BROW_RAISER] = (c("browOuterUpLeft") + c("browOuterUpRight")) / 2f
        a[AU4_BROW_LOWERER] = (c("browDownLeft") + c("browDownRight")) / 2f
        a[AU5_UPPER_LID_RAISER] = (c("eyeWideLeft") + c("eyeWideRight")) / 2f
        a[AU6_CHEEK_RAISER] = (c("cheekSquintLeft") + c("cheekSquintRight")) / 2f
        a[AU7_LID_TIGHTENER] = (c("eyeSquintLeft") + c("eyeSquintRight")) / 2f
        a[AU9_NOSE_WRINKLER] = (c("noseSneerLeft") + c("noseSneerRight")) / 2f
        a[AU10_UPPER_LIP_RAISER] = (c("mouthUpperUpLeft") + c("mouthUpperUpRight")) / 2f
        a[AU12_LIP_CORNER_PULLER] = (c("mouthSmileLeft") + c("mouthSmileRight")) / 2f
        a[AU14_DIMPLER] = (c("mouthDimpleLeft") + c("mouthDimpleRight")) / 2f
        a[AU15_LIP_CORNER_DEPRESSOR] = (c("mouthFrownLeft") + c("mouthFrownRight")) / 2f
        a[AU16_LOWER_LIP_DEPRESSOR] = (c("mouthLowerDownLeft") + c("mouthLowerDownRight")) / 2f
        a[AU17_CHIN_RAISER] = maxOf(c("mouthShrugLower"), 0.6f * c("mouthShrugUpper"))
        a[AU18_LIP_PUCKER] = c("mouthPucker")
        a[AU20_LIP_STRETCHER] = (c("mouthStretchLeft") + c("mouthStretchRight")) / 2f
        a[AU22_LIP_FUNNELER] = c("mouthFunnel")
        a[AU24_LIP_PRESSOR] = (c("mouthPressLeft") + c("mouthPressRight")) / 2f
        a[AU26_JAW_DROP] = c("jawOpen")
        a[AU28_LIP_SUCK] = (c("mouthRollLower") + c("mouthRollUpper")) / 2f
        a[AU33_CHEEK_BLOW] = c("cheekPuff")
        a[AU43_EYES_CLOSED] = (c("eyeBlinkLeft") + c("eyeBlinkRight")) / 2f
        // Asymmetry axes: computed from the raw (corrected) L/R deltas directly,
        // before any L/R mean is taken above.
        a[ASYM_SMILE] = abs(c("mouthSmileLeft") - c("mouthSmileRight"))
        a[ASYM_BROW] = maxOf(
            abs(c("browOuterUpLeft") - c("browOuterUpRight")),
            abs(c("browDownLeft") - c("browDownRight"))
        )
        a[ASYM_BLINK] = abs(c("eyeBlinkLeft") - c("eyeBlinkRight"))
        a[GAZE_UP] = (c("eyeLookUpLeft") + c("eyeLookUpRight")) / 2f
        a[TONGUE] = c("tongueOut")
        return a
    }
}
