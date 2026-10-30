package com.shabana.filekhoj

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores profiles only on this device. There is deliberately no cloud account backend.
 * The installer explicitly chooses whether the device is configured as Admin or User.
 */
data class LocalUserProfile(
    val name: String,
    val mobile: String,
    val location: String,
    val role: String,
    val registeredAt: Long,
    val notificationConfirmed: Boolean
)

class UserRegistrationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "filekhoj_registration",
        Context.MODE_PRIVATE
    )

    fun profiles(): List<LocalUserProfile> {
        val raw = preferences.getString(KEY_PROFILES, "[]") ?: "[]"
        return try {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.optJSONObject(index) ?: continue
                    add(
                        LocalUserProfile(
                            name = item.optString("name"),
                            mobile = item.optString("mobile"),
                            location = item.optString("location"),
                            role = item.optString("role", ROLE_USER),
                            registeredAt = item.optLong("registeredAt"),
                            notificationConfirmed = item.optBoolean("notificationConfirmed", false)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun activeProfile(): LocalUserProfile? {
        val mobile = preferences.getString(KEY_ACTIVE_MOBILE, null) ?: return null
        return profiles().firstOrNull { it.mobile == mobile }
    }

    fun setActiveProfile(profile: LocalUserProfile) {
        preferences.edit().putString(KEY_ACTIVE_MOBILE, profile.mobile).apply()
    }

    fun register(name: String, mobile: String, location: String, registerAsAdmin: Boolean): LocalUserProfile {
        require(name.isNotBlank()) { "Enter your name." }
        require(isValidIndianMobile(mobile)) { "Enter a valid 10-digit Indian mobile number." }
        require(location.isNotBlank()) { "Enter your village, town or city." }

        val current = profiles()
        require(current.none { it.mobile == normalizeMobile(mobile) }) {
            "A profile with this mobile number is already registered on this device."
        }

        val hasAdmin = current.any { it.role == ROLE_ADMIN }
        require(!registerAsAdmin || !hasAdmin) {
            "An administrator profile already exists on this device."
        }
        if (!registerAsAdmin) {
            require(adminWhatsappNumber().isNotBlank()) {
                "Set the admin WhatsApp number before registering as a user."
            }
        }
        val role = if (registerAsAdmin) ROLE_ADMIN else ROLE_USER
        val profile = LocalUserProfile(
            name = name.trim(),
            mobile = normalizeMobile(mobile),
            location = location.trim(),
            role = role,
            registeredAt = System.currentTimeMillis(),
            notificationConfirmed = role == ROLE_ADMIN
        )
        persist(current + profile)
        if (role == ROLE_ADMIN) {
            setAdminWhatsappNumber(profile.mobile)
        }
        setActiveProfile(profile)
        return profile
    }

    fun confirmAdminNotification(mobile: String) {
        val updated = profiles().map {
            if (it.mobile == mobile) it.copy(notificationConfirmed = true) else it
        }
        persist(updated)
    }

    fun adminWhatsappNumber(): String {
        val configured = preferences.getString(KEY_ADMIN_WHATSAPP, "").orEmpty()
        if (configured.isNotBlank()) return normalizeWhatsappNumber(configured)
        return profiles().firstOrNull { it.role == ROLE_ADMIN }?.mobile
            ?.let(::normalizeWhatsappNumber).orEmpty()
    }

    fun setAdminWhatsappNumber(number: String) {
        val normalized = normalizeWhatsappNumber(number)
        require(normalized.length in 10..15) { "Enter a valid admin WhatsApp number with country code." }
        preferences.edit().putString(KEY_ADMIN_WHATSAPP, normalized).apply()
    }

    private fun persist(profiles: List<LocalUserProfile>) {
        val json = JSONArray()
        profiles.forEach { profile ->
            json.put(
                JSONObject()
                    .put("name", profile.name)
                    .put("mobile", profile.mobile)
                    .put("location", profile.location)
                    .put("role", profile.role)
                    .put("registeredAt", profile.registeredAt)
                    .put("notificationConfirmed", profile.notificationConfirmed)
            )
        }
        preferences.edit().putString(KEY_PROFILES, json.toString()).apply()
    }

    companion object {
        const val ROLE_ADMIN = "ADMIN"
        const val ROLE_USER = "USER"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE_MOBILE = "active_mobile"
        private const val KEY_ADMIN_WHATSAPP = "admin_whatsapp"

        fun isValidIndianMobile(value: String): Boolean {
            val digits = value.filter(Char::isDigit).removePrefix("91").let {
                if (it.length > 10) it.takeLast(10) else it
            }
            return digits.matches(Regex("[6-9][0-9]{9}"))
        }

        fun normalizeMobile(value: String): String {
            val digits = value.filter(Char::isDigit)
            return if (digits.length == 12 && digits.startsWith("91")) digits else digits.takeLast(10)
        }

        fun normalizeWhatsappNumber(value: String): String {
            val digits = value.filter(Char::isDigit)
            return when {
                digits.length == 10 -> "91$digits"
                digits.length in 11..15 -> digits
                else -> ""
            }
        }
    }
}
