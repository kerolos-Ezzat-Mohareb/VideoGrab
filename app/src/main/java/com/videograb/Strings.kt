package com.videograb

import android.content.Context

object L {
    @Volatile var ar = false

    fun load(c: Context) {
        ar = c.getSharedPreferences("vg", Context.MODE_PRIVATE).getBoolean("ar", false)
    }

    fun save(c: Context, value: Boolean) {
        ar = value
        c.getSharedPreferences("vg", Context.MODE_PRIVATE).edit().putBoolean("ar", value).apply()
    }

    fun t(en: String, arabic: String): String = if (ar) arabic else en
}
