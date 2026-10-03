package com.sempermechanics.semper.ui.common

import android.app.Application
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The numeric-field and toggle-group helpers on real, attached widgets. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KeyboardTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var field: EditText
    private lateinit var other: EditText

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        activity = built.setup().get()
        field = EditText(activity)
        other = EditText(activity)
        activity.setContentView(
            LinearLayout(activity).apply {
                // A focusable parent, so clearFocus() leaves the field unfocused.
                isFocusableInTouchMode = true
                addView(field)
                addView(other)
            },
        )
    }

    private fun imm() = shadowOf(activity.getSystemService(InputMethodManager::class.java))

    @Test
    fun `hideKeyboard hides the soft keyboard`() {
        field.requestFocus()
        activity.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
        assertTrue(imm().isSoftInputVisible)

        field.hideKeyboard()

        assertFalse(imm().isSoftInputVisible)
    }

    @Test
    fun `Done commits, drops focus and hides the keyboard`() {
        var commits = 0
        field.commitOnDone { commits++ }
        field.requestFocus()
        activity.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)

        field.onEditorAction(EditorInfo.IME_ACTION_DONE)

        assertEquals(1, commits)
        assertFalse(field.hasFocus())
        assertFalse(imm().isSoftInputVisible)
    }

    @Test
    fun `other keyboard actions are left to the field`() {
        var commits = 0
        field.commitOnDone { commits++ }
        field.requestFocus()

        field.onEditorAction(EditorInfo.IME_ACTION_NEXT)

        // The field's own Next handling ran: focus moved on to the next field.
        assertEquals(0, commits)
        assertTrue(other.hasFocus())
    }

    @Test
    fun `a chosen action, such as Go, is the one that commits`() {
        var commits = 0
        field.commitOnDone(EditorInfo.IME_ACTION_GO) { commits++ }
        field.requestFocus()

        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertEquals(0, commits)
        field.onEditorAction(EditorInfo.IME_ACTION_GO)
        assertEquals(1, commits)
        assertFalse(field.hasFocus())
    }

    @Test
    fun `showUnlessEditing leaves a field being typed in alone`() {
        field.showUnlessEditing("41")
        assertEquals("41", field.text.toString())

        field.requestFocus()
        field.setText("4")
        field.showUnlessEditing("41")
        assertEquals("4", field.text.toString())
    }

    @Test
    fun `onButtonChecked reports checks, not unchecks`() {
        val group = MaterialButtonToggleGroup(activity).apply { isSingleSelection = true }
        val a = MaterialButton(activity).apply { id = R.id.rbErase }
        val b = MaterialButton(activity).apply { id = R.id.btnModeKeyframes }
        group.addView(a)
        group.addView(b)
        val checked = mutableListOf<Int>()
        group.onButtonChecked { checked += it }

        group.check(a.id)
        group.check(b.id) // unchecks a, checks b
        group.uncheck(b.id)

        assertEquals(listOf(a.id, b.id), checked)
    }
}
