package com.sempermechanics.semper.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * One SharedPreferences entry: its stored [name], its type, and the [default]
 * a read returns when nothing is stored.
 *
 * The app's preference files spelled each key as a private string constant and
 * repeated the type and the default at every `getX(KEY, default)`. A key here
 * carries all three, so a read and a write can no longer disagree on them.
 * [name] is the stored key, byte for byte: renaming one orphans every value
 * already on a phone. The catalogue of existing keys is [PrefFiles].
 *
 * Read with `prefs[key]`, write inside `prefs.edit { put(key, value) }`.
 */
class PrefKey<T> private constructor(
    val name: String,
    val default: T,
    private val reader: SharedPreferences.(String, T) -> T,
    private val writer: SharedPreferences.Editor.(String, T) -> Unit,
) {
    internal fun read(prefs: SharedPreferences): T = prefs.reader(name, default)

    internal fun write(editor: SharedPreferences.Editor, value: T) = editor.writer(name, value)

    override fun toString(): String = "PrefKey($name)"

    companion object {
        fun boolean(name: String, default: Boolean = false): PrefKey<Boolean> =
            PrefKey(name, default, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

        fun int(name: String, default: Int = 0): PrefKey<Int> =
            PrefKey(name, default, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })

        fun long(name: String, default: Long = 0L): PrefKey<Long> =
            PrefKey(name, default, { k, d -> getLong(k, d) }, { k, v -> putLong(k, v) })

        /** A string that may be absent: reads null when unset, and writing null removes it. */
        fun string(name: String): PrefKey<String?> =
            PrefKey(name, null, { k, d -> getString(k, d) }, { k, v -> putString(k, v) })

        /** A string read as [default] when unset (`getString(key, default) ?: default`). */
        fun string(name: String, default: String): PrefKey<String> =
            PrefKey(name, default, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })

        /**
         * A string set, read as an empty set when unset. The read is a copy:
         * the set SharedPreferences hands out must not be modified.
         */
        fun stringSet(name: String): PrefKey<Set<String>> =
            PrefKey(name, emptySet(), { k, d -> getStringSet(k, null)?.toSet() ?: d }, { k, v -> putStringSet(k, v) })
    }
}

/** The private preference file [name], opened on the application context. */
fun privatePrefs(context: Context, name: String): SharedPreferences =
    context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

/** The stored value of [key], or its default. */
operator fun <T> SharedPreferences.get(key: PrefKey<T>): T = key.read(this)

/** Whether a value is stored under [key]. */
operator fun SharedPreferences.contains(key: PrefKey<*>): Boolean = contains(key.name)

/** Stores [value] under [key]; for a nullable string key, null removes it. */
fun <T> SharedPreferences.Editor.put(key: PrefKey<T>, value: T): SharedPreferences.Editor {
    key.write(this, value)
    return this
}

/** Removes whatever is stored under [key]. */
fun SharedPreferences.Editor.remove(key: PrefKey<*>): SharedPreferences.Editor = remove(key.name)
