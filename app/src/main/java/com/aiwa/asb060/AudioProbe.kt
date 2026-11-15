package com.aiwa.asb060

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.KeyEvent

/** Ce que la recherche observe sur le téléphone pour savoir si la barre de son a réagi. */
interface Probe {
    /** Une musique est en cours de lecture sur le téléphone. */
    fun musicActive(): Boolean

    /** Le son du téléphone sort par un appareil Bluetooth (la barre de son). */
    fun btConnected(): Boolean

    /** Volume « média » du téléphone ; en Bluetooth il suit le volume réglé sur la barre. */
    fun volume(): Int
    fun setVolume(v: Int)

    /** Relance / met en pause le lecteur de musique du téléphone. */
    fun resumeMusic()
    fun pauseMusic()
}

class AudioProbe(context: Context) : Probe {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun musicActive(): Boolean = audio.isMusicActive

    override fun btConnected(): Boolean = try {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
    } catch (e: Exception) {
        false
    }

    override fun volume(): Int = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun setVolume(v: Int) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        } catch (e: Exception) {
            // Refusé en mode « Ne pas déranger » : sans importance.
        }
    }

    override fun resumeMusic() = mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)

    override fun pauseMusic() = mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)

    private fun mediaKey(code: Int) {
        try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (e: Exception) {
            // Aucun lecteur ne répond : la vérification automatique sera simplement abandonnée.
        }
    }
}
