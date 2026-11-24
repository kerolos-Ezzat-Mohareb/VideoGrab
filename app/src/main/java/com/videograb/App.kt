package com.videograb

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Theme.mode(this))
    }
}

object Theme {
    fun get(c: Context): Int =
        c.getSharedPreferences("vg", Context.MODE_PRIVATE).getInt("theme", 0)

    fun set(c: Context, v: Int) {
        c.getSharedPreferences("vg", Context.MODE_PRIVATE).edit().putInt("theme", v).apply()
    }

    fun mode(c: Context): Int = when (get(c)) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}
