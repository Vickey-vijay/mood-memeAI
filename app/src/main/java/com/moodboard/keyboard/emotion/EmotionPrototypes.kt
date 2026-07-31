package com.moodboard.keyboard.emotion

/**
 * FACS prototype table (see SPEC_V2 A.5), grounded in Ekman & Friesen's EMFACS AU
 * combinations, mapped onto the ARKit blendshape rig via [ActionUnits]. Each
 * prototype is a 26-dim vector over the AU axes with entries in 0..1 expressing
 * the relative expected activation of that action. NEUTRAL has no prototype — it
 * is the outcome of the intensity gate in [ExpressionClassifier].
 */
object EmotionPrototypes {

    private fun vec(vararg pairs: Pair<Int, Float>): FloatArray {
        val v = FloatArray(ActionUnits.COUNT)
        for ((i, w) in pairs) v[i] = w
        return v
    }

    private val RAW: Map<Emotion, FloatArray> = mapOf(
        Emotion.HAPPY to vec(
            ActionUnits.AU6_CHEEK_RAISER to 0.60f,
            ActionUnits.AU12_LIP_CORNER_PULLER to 1.0f,
            ActionUnits.AU14_DIMPLER to 0.30f
        ),
        Emotion.LAUGHING to vec(
            ActionUnits.AU6_CHEEK_RAISER to 0.80f,
            ActionUnits.AU12_LIP_CORNER_PULLER to 1.0f,
            ActionUnits.AU26_JAW_DROP to 0.80f,
            ActionUnits.AU7_LID_TIGHTENER to 0.40f
        ),
        Emotion.EXCITED to vec(
            ActionUnits.AU12_LIP_CORNER_PULLER to 0.80f,
            ActionUnits.AU5_UPPER_LID_RAISER to 0.70f,
            ActionUnits.AU2_OUTER_BROW_RAISER to 0.60f,
            ActionUnits.AU26_JAW_DROP to 0.40f
        ),
        Emotion.SURPRISED to vec(
            ActionUnits.AU1_INNER_BROW_RAISER to 0.90f,
            ActionUnits.AU2_OUTER_BROW_RAISER to 1.0f,
            ActionUnits.AU5_UPPER_LID_RAISER to 0.80f,
            ActionUnits.AU26_JAW_DROP to 0.70f
        ),
        Emotion.SHOCKED to vec(
            ActionUnits.AU1_INNER_BROW_RAISER to 0.80f,
            ActionUnits.AU2_OUTER_BROW_RAISER to 0.80f,
            ActionUnits.AU5_UPPER_LID_RAISER to 1.0f,
            ActionUnits.AU26_JAW_DROP to 1.0f,
            ActionUnits.AU20_LIP_STRETCHER to 0.40f
        ),
        Emotion.FEARFUL to vec(
            ActionUnits.AU1_INNER_BROW_RAISER to 0.90f,
            ActionUnits.AU2_OUTER_BROW_RAISER to 0.60f,
            ActionUnits.AU4_BROW_LOWERER to 0.50f,
            ActionUnits.AU5_UPPER_LID_RAISER to 1.0f,
            ActionUnits.AU7_LID_TIGHTENER to 0.40f,
            ActionUnits.AU20_LIP_STRETCHER to 0.80f,
            ActionUnits.AU26_JAW_DROP to 0.50f
        ),
        Emotion.SAD to vec(
            ActionUnits.AU1_INNER_BROW_RAISER to 0.90f,
            ActionUnits.AU4_BROW_LOWERER to 0.50f,
            ActionUnits.AU15_LIP_CORNER_DEPRESSOR to 1.0f,
            ActionUnits.AU17_CHIN_RAISER to 0.50f
        ),
        Emotion.ANGRY to vec(
            ActionUnits.AU4_BROW_LOWERER to 1.0f,
            ActionUnits.AU5_UPPER_LID_RAISER to 0.50f,
            ActionUnits.AU7_LID_TIGHTENER to 0.70f,
            ActionUnits.AU24_LIP_PRESSOR to 0.80f,
            ActionUnits.AU9_NOSE_WRINKLER to 0.30f
        ),
        Emotion.ANNOYED to vec(
            ActionUnits.AU4_BROW_LOWERER to 0.60f,
            ActionUnits.AU7_LID_TIGHTENER to 0.80f,
            ActionUnits.AU24_LIP_PRESSOR to 0.70f,
            ActionUnits.GAZE_UP to 0.60f,
            ActionUnits.AU14_DIMPLER to 0.20f
        ),
        Emotion.FRUSTRATED to vec(
            ActionUnits.AU4_BROW_LOWERER to 0.80f,
            ActionUnits.AU15_LIP_CORNER_DEPRESSOR to 0.60f,
            ActionUnits.AU17_CHIN_RAISER to 0.60f,
            ActionUnits.AU24_LIP_PRESSOR to 0.50f,
            ActionUnits.AU7_LID_TIGHTENER to 0.40f
        ),
        Emotion.DISGUST to vec(
            ActionUnits.AU9_NOSE_WRINKLER to 1.0f,
            ActionUnits.AU10_UPPER_LIP_RAISER to 0.80f,
            ActionUnits.AU15_LIP_CORNER_DEPRESSOR to 0.50f,
            ActionUnits.AU16_LOWER_LIP_DEPRESSOR to 0.40f,
            ActionUnits.AU4_BROW_LOWERER to 0.30f
        ),
        Emotion.CONTEMPT to vec(
            ActionUnits.ASYM_SMILE to 1.0f,
            ActionUnits.AU14_DIMPLER to 0.60f,
            ActionUnits.AU12_LIP_CORNER_PULLER to 0.40f
        ),
        Emotion.SKEPTICAL to vec(
            ActionUnits.ASYM_BROW to 1.0f,
            ActionUnits.AU2_OUTER_BROW_RAISER to 0.50f,
            ActionUnits.AU7_LID_TIGHTENER to 0.40f,
            ActionUnits.AU24_LIP_PRESSOR to 0.30f
        ),
        Emotion.SLEEPY to vec(
            ActionUnits.AU43_EYES_CLOSED to 0.80f,
            ActionUnits.AU7_LID_TIGHTENER to 0.50f,
            ActionUnits.AU26_JAW_DROP to 0.40f,
            ActionUnits.AU1_INNER_BROW_RAISER to 0.30f
        ),
        Emotion.KISS to vec(
            ActionUnits.AU18_LIP_PUCKER to 1.0f,
            ActionUnits.AU22_LIP_FUNNELER to 0.50f
        ),
        Emotion.WINK to vec(
            ActionUnits.ASYM_BLINK to 1.0f,
            ActionUnits.AU12_LIP_CORNER_PULLER to 0.50f,
            ActionUnits.AU6_CHEEK_RAISER to 0.40f
        ),
        Emotion.PUFFED to vec(
            ActionUnits.AU33_CHEEK_BLOW to 1.0f,
            ActionUnits.AU24_LIP_PRESSOR to 0.40f
        ),
        Emotion.SILLY to vec(
            ActionUnits.TONGUE to 1.0f,
            ActionUnits.AU26_JAW_DROP to 0.50f,
            ActionUnits.AU12_LIP_CORNER_PULLER to 0.40f
        )
    )

    /**
     * Prototypes to score against. SILLY is excluded when [tongueSupported] is
     * false — the runtime capability probe from SPEC_V2 A.3: don't ship an
     * emotion the on-device model can't reliably see.
     */
    fun active(tongueSupported: Boolean): Map<Emotion, FloatArray> =
        if (tongueSupported) RAW else RAW - Emotion.SILLY
}
