// TaskVisionKeys.kt — Axon Task Engine V2: مفاتيح متعددة لكل مزود + rotation عند نفاد الحصة
// التخزين: pref منفصل vision_keys_<CHOICE> (csv) — يُزرع أول مرة من مفتاح الإعدادات الحالي.
// الشات الأساسي لا يتأثر (يستخدم مفتاحه الواحد من prefs كما هو).
package com.example.app_abdelbaset

import android.content.Context
import android.util.Log

object TaskVisionKeys {

    private const val TAG = "TaskEngine"
    private const val PREFS = "axon_prefs"
    private const val PREFIX = "vision_keys_"
    private const val IDX_PREFIX = "vision_key_idx_"

    // أسماء prefs المفاتيح المفردة الحالية (للزرع الأول فقط — نفس القيم في المزودين)
    private const val SINGLE_COHERE = "cohere_api_key"
    private const val SINGLE_GEMINI = "gemini_api_key"

    /** مفتاح + حالة cooldown */
    data class KeySlot(val key: String, val index: Int)

    private val cooldownUntil = java.util.Collections.synchronizedMap(mutableMapOf<String, Long>())

    private fun storeKey(choice: TaskVisionProvider.VisionChoice): String {
        val base = when (choice) {
            TaskVisionProvider.VisionChoice.COHERE_VISION,
            TaskVisionProvider.VisionChoice.COHERE_PLUS -> PREFIX + "COHERE"
            TaskVisionProvider.VisionChoice.GEMINI -> PREFIX + "GEMINI"
            // LOCAL بلا مفاتيح (on-device) — الـ reasoner يتجاوزه أصلًا
            TaskVisionProvider.VisionChoice.LOCAL -> PREFIX + "LOCAL"
        }
        return base
    }

    private fun singleKeyName(choice: TaskVisionProvider.VisionChoice): String =
        when (choice) {
            TaskVisionProvider.VisionChoice.COHERE_VISION,
            TaskVisionProvider.VisionChoice.COHERE_PLUS -> SINGLE_COHERE
            TaskVisionProvider.VisionChoice.GEMINI -> SINGLE_GEMINI
            TaskVisionProvider.VisionChoice.LOCAL -> ""
        }

    /** كل المفاتيح (تُزرع من المفرد الحالي أول مرة) */
    fun getKeys(context: Context, choice: TaskVisionProvider.VisionChoice): List<String> {
        return try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var raw = p.getString(storeKey(choice), null)
            if (raw.isNullOrBlank()) {
                val single = p.getString(singleKeyName(choice), "").orEmpty().trim()
                if (single.isNotBlank()) {
                    p.edit().putString(storeKey(choice), single).apply()
                    raw = single
                }
            }
            raw.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
        } catch (_: Exception) { emptyList() }
    }

    fun setKeysCsv(context: Context, choice: TaskVisionProvider.VisionChoice, csv: String): Int {
        return try {
            val clean = csv.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(storeKey(choice), clean.joinToString(","))
                .putInt(IDX_PREFIX + choice.name, 0).apply()
            synchronized(cooldownUntil) { cooldownUntil.keys.removeAll { it.startsWith(choice.name + "#") } }
            clean.size
        } catch (_: Exception) { 0 }
    }

    fun count(context: Context, choice: TaskVisionProvider.VisionChoice): Int =
        getKeys(context, choice).size

    fun activeIndex(context: Context, choice: TaskVisionProvider.VisionChoice): Int = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(IDX_PREFIX + choice.name, 0)
    } catch (_: Exception) { 0 }

    private fun setActiveIndex(context: Context, choice: TaskVisionProvider.VisionChoice, idx: Int) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(IDX_PREFIX + choice.name, idx).apply()
        } catch (_: Exception) {}
    }

    private fun slotId(choice: TaskVisionProvider.VisionChoice, idx: Int) = "${choice.name}#$idx"

    fun markQuotaHit(choice: TaskVisionProvider.VisionChoice, idx: Int, retryAfterSec: Long) {
        val until = System.currentTimeMillis() + (retryAfterSec.coerceIn(5, 300) * 1000L)
        synchronized(cooldownUntil) { cooldownUntil[slotId(choice, idx)] = until }
        Log.w(TAG, "VisionKey ${slotId(choice, idx)} cooling until +${retryAfterSec}s")
    }

    fun inCooldown(choice: TaskVisionProvider.VisionChoice, idx: Int): Boolean {
        val until = synchronized(cooldownUntil) { cooldownUntil[slotId(choice, idx)] } ?: return false
        if (System.currentTimeMillis() >= until) {
            synchronized(cooldownUntil) { cooldownUntil.remove(slotId(choice, idx)) }
            return false
        }
        return true
    }

    /**
     * ترتيب المحاولة: من الـ activeIndex، غير المبرد أولًا، ثم المبرد (كملاذ أخير).
     * تُنادى لكل vision call — والـ activeIndex يتحدث عند أول نجاح.
     */
    fun orderedSlots(
        context: Context,
        choice: TaskVisionProvider.VisionChoice
    ): List<KeySlot> {
        val keys = getKeys(context, choice)
        if (keys.isEmpty()) return emptyList()
        val start = activeIndex(context, choice).coerceIn(0, maxOf(0, keys.size - 1))
        val ordered = (keys.indices).map { KeySlot(keys[(start + it) % keys.size], (start + it) % keys.size) }
        val fresh = ordered.filter { !inCooldown(choice, it.index) }
        val cooling = ordered.filter { inCooldown(choice, it.index) }
        if (cooling.isNotEmpty()) {
            Log.w(TAG, "VisionKey $choice: ${cooling.size}/${keys.size} in cooldown — fresh first")
        }
        return fresh + cooling
    }

    fun markSuccess(context: Context, choice: TaskVisionProvider.VisionChoice, idx: Int) {
        setActiveIndex(context, choice, idx)
        synchronized(cooldownUntil) { cooldownUntil.remove(slotId(choice, idx)) }
    }
}
