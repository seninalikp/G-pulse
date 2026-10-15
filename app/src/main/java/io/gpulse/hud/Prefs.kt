package io.gpulse.hud

import android.content.Context
import java.util.TimeZone

object Prefs {
    private const val FILE = "gpulse_prefs"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun address(c: Context): String? = sp(c).getString("address", null)
    fun watchName(c: Context): String? = sp(c).getString("watch_name", null)
    fun getSavedAddress(c: Context): String? = address(c)
    fun getSavedName(c: Context): String? = watchName(c)

    fun saveWatch(c: Context, address: String, name: String) {
        sp(c).edit().putString("address", address).putString("watch_name", name).apply()
    }

    fun forgetWatch(c: Context) {
        sp(c).edit()
            .remove("address")
            .remove("watch_name")
            .putBoolean("keep_running", false)
            .apply()
    }

    // Personalized watch display name (e.g. user's custom nickname)
    fun getCustomWatchLabel(c: Context): String =
        sp(c).getString("custom_watch_label", "") ?: ""

    fun setCustomWatchLabel(c: Context, label: String) =
        sp(c).edit().putString("custom_watch_label", label.trim()).apply()

    // Auto-Connect toggle on main page
    fun isAutoConnect(c: Context): Boolean = sp(c).getBoolean("auto_connect", true)
    fun setAutoConnect(c: Context, enabled: Boolean) =
        sp(c).edit().putBoolean("auto_connect", enabled).apply()

    // Selected Timezone for Watch Sync
    fun getSelectedTimezone(c: Context): String =
        sp(c).getString("selected_timezone", null) ?: TimeZone.getDefault().id

    fun setSelectedTimezone(c: Context, tzId: String) =
        sp(c).edit().putString("selected_timezone", tzId).apply()

    fun keepRunning(c: Context): Boolean = sp(c).getBoolean("keep_running", false)
    fun setKeepRunning(c: Context, v: Boolean) = sp(c).edit().putBoolean("keep_running", v).apply()

    // 4-Quadrant VIP Contact Mapping (Slots: 12, 3, 6, 9)
    fun getVipContact(c: Context, slot: Int): String =
        sp(c).getString("vip_slot_$slot", "") ?: ""

    fun setVipContact(c: Context, slot: Int, value: String) =
        sp(c).edit().putString("vip_slot_$slot", value.trim()).apply()
}
