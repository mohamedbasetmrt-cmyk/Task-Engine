// TaskVoiceReporter.kt — Axon Task Engine V1: جمل ثابتة للشات كنص (TTS hook جاهز ومطفي)
package com.example.app_abdelbaset

object TaskVoiceReporter {
    fun started(): String = "تمام، بدأت."
    fun completed(): String = "خلصت."
    fun failed(reason: String = ""): String =
        if (reason.isBlank()) "حصلت مشكلة ومقدرتش أكمل."
        else "حصلت مشكلة ومقدرتش أكمل. ($reason)"
    fun unsupported(): String = "مش مدعوم. جرّب: افتح الاعدادات."
    fun cancelled(): String = "اتلغى."
}
