/*
 * Copyright 2026 Lambda
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.lambda.module.modules.player

import com.lambda.Lambda.mc
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.module.Module
import com.lambda.module.tag.ModuleTag
import com.lambda.util.CommunicationUtils.LogLevel
import com.lambda.util.CommunicationUtils.toast
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.Describable
import com.lambda.util.NamedEnum
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.util.Util
import net.minecraft.util.math.Vec3d
import org.lwjgl.glfw.GLFW
import java.io.File

@Suppress("unused")
object StuckAlarm : Module(
    name = "StuckAlarm",
    description = "Alerts you when you stop moving, e.g. when a digger or highway bot gets stuck",
    tag = ModuleTag.PLAYER,
) {
    private val timeout by setting("Timeout", 30, 5..600, 1, "How long you must not move before the alarm goes off", " s")
    private val minDistance by setting("Min Distance", 2.0, 0.5..32.0, 0.5, "Distance to move within the timeout to count as moving", " blocks")
    private val ignoreVertical by setting("Ignore Vertical", false, "Only count horizontal movement")
    private val mode by setting("Mode", AlarmMode.Once, "Alarm once, or keep alarming until you react")
    private val repeatInterval by setting("Repeat Interval", 30, 5..600, 1, "How often the alarm repeats", " s") { mode == AlarmMode.Repeat }
    private val desktopNotification by setting("Desktop Notification", true, "Show an OS notification that stays until you close it")
    private val osSound by setting("OS Sound", OsSound.SoftChime, "Sound played through the OS, independent of the game volume")
    private val osVolume by setting("OS Volume", 0.7, 0.0..1.0, 0.05, "Volume of the OS sound") { osSound != OsSound.None }
    private val sound by setting("Sound", AlarmSound.None, "In-game alarm sound")
    private val volume by setting("Volume", 1.0, 0.0..1.0, 0.05, "Volume of the in-game sound") { sound != AlarmSound.None }
    private val chat by setting("Chat", true, "Send a chat message")
    private val toast by setting("Toast", true, "Show a toast")
    private val flashWindow by setting("Flash Window", true, "Flash the taskbar")

    enum class AlarmMode(
        override val displayName: String,
        override val description: String,
    ) : NamedEnum, Describable {
        Once("Once", "Alarm a single time, then only the notification stays"),
        Repeat("Repeat", "Keep alarming every repeat interval until you close the notification or move again"),
    }

    enum class OsSound(
        override val displayName: String,
        override val description: String,
        val id: String?,
    ) : NamedEnum, Describable {
        SoftChime("Soft Chime", "Gentle two-tone chime", "message-attention"),
        GentlePing("Gentle Ping", "Soft message ping", "message-new-instant"),
        SoftBell("Soft Bell", "Quiet bell", "bell"),
        Success("Success", "Light rising tone", "completion-success"),
        AlarmClock("Alarm Clock", "Loud alarm clock", "alarm-clock-elapsed"),
        None("None", "No OS sound", null),
    }

    enum class AlarmSound(
        override val displayName: String,
        override val description: String,
        val event: () -> SoundEvent?,
    ) : NamedEnum, Describable {
        Pling("Pling", "Note block pling", { SoundEvents.BLOCK_NOTE_BLOCK_PLING.value() }),
        Bell("Bell", "Village bell", { SoundEvents.BLOCK_BELL_USE }),
        Anvil("Anvil", "Anvil landing", { SoundEvents.BLOCK_ANVIL_LAND }),
        LevelUp("Level Up", "Experience level up", { SoundEvents.ENTITY_PLAYER_LEVELUP }),
        None("None", "No sound", { null }),
    }

    private const val TITLE = "Lambda - StuckAlarm"

    /** KDE's Ocean sounds are softer, so they're preferred when installed. */
    private val soundDirs = listOf("/usr/share/sounds/ocean/stereo", "/usr/share/sounds/freedesktop/stereo")

    private val isLinux = Util.getOperatingSystem() == Util.OperatingSystem.LINUX
    private val isMac = Util.getOperatingSystem() == Util.OperatingSystem.OSX

    private var anchor: Vec3d? = null
    private var anchorTime = 0L
    private var lastAlarm = 0L
    private var alarming = false

    /** The last notification. A new one replaces it, so they don't pile up over a long session. */
    @Volatile private var notification: DesktopNotification? = null

    /** [process] exits once the notification is closed. [id] lets a new one replace it, where that's possible. */
    private class DesktopNotification(val process: Process, val id: String?)

    init {
        onEnable { reset() }
        onDisable {
            reset()
            notification?.process?.destroy()
        }

        listen<TickEvent.Pre> {
            val now = System.currentTimeMillis()
            val start = anchor

            if (start == null || moved(start, player.pos)) {
                if (alarming) warn("Moving again")
                reset()
                anchor = player.pos
                anchorTime = now
                return@listen
            }

            val stuckFor = now - anchorTime
            if (stuckFor < timeout * 1000L) return@listen

            if (alarming) {
                if (mode == AlarmMode.Once || !notificationOpen()) return@listen
                if (now - lastAlarm < repeatInterval * 1000L) return@listen
            }

            val message = message(stuckFor)
            if (!alarming && desktopNotification) Util.getIoWorkerExecutor().execute { sendDesktopNotification(message) }
            alarming = true
            lastAlarm = now
            alarm(message)
        }
    }

    private fun alarm(message: String) {
        sound.event()?.let { mc.soundManager.play(PositionedSoundInstance.ui(it, 1f, volume.toFloat())) }
        if (chat) warn(message)
        if (toast) toast(message, LogLevel.Warn)
        if (flashWindow) GLFW.glfwRequestWindowAttention(mc.window.handle)
        Util.getIoWorkerExecutor().execute { playOsSound() }
    }

    private fun message(stuckFor: Long) = "You haven't moved for ${stuckFor / 1000}s!"

    private fun moved(from: Vec3d, to: Vec3d): Boolean {
        val delta = to.subtract(from)
        val distance = if (ignoreVertical) delta.horizontalLength() else delta.length()
        return distance >= minDistance
    }

    /** True while the notification is still shown, or when there is none to dismiss. */
    private fun notificationOpen() = notification?.process?.isAlive != false

    /** `notify-send --wait` and `display alert` both block until the notification is closed. */
    @Synchronized
    private fun sendDesktopNotification(message: String) {
        val previous = notification
        previous?.process?.destroy()
        val sent = when {
            isLinux -> {
                val replace = listOfNotNull(previous?.id?.let { "--replace-id=$it" })
                val command = listOf("notify-send", "--wait", "--print-id", "-u", "critical", "-t", "0", "-a", "Lambda", "-i", "dialog-warning") +
                    replace + listOf(TITLE, message)
                exec(*command.toTypedArray(), readOutput = true)
                    ?.let { DesktopNotification(it, runCatching { it.inputReader().readLine()?.trim() }.getOrNull()) }
            }
            isMac -> exec("osascript", "-e", "display alert \"$TITLE\" message \"$message\" as critical")?.let { DesktopNotification(it, null) }
            else -> null
        }
        // Turned off while it was being sent.
        if (!isEnabled) sent?.process?.destroy()
        notification = sent
    }

    private fun playOsSound() {
        val id = osSound.id ?: return
        when {
            isLinux -> {
                val file = soundDirs.map { File(it, "$id.oga") }.firstOrNull { it.exists() }?.path
                if (file == null) {
                    exec("canberra-gtk-play", "-i", id)
                    return
                }
                exec("pw-play", "--volume=$osVolume", file)
                    ?: exec("paplay", "--volume=${(osVolume * 65536).toInt()}", file)
            }
            isMac -> exec("afplay", "-v", "$osVolume", "/System/Library/Sounds/Sosumi.aiff")
        }
    }

    private fun exec(vararg command: String, readOutput: Boolean = false): Process? = runCatching {
        ProcessBuilder(*command)
            .redirectOutput(if (readOutput) ProcessBuilder.Redirect.PIPE else ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.getOrNull()

    private fun reset() {
        anchor = null
        alarming = false
    }
}
