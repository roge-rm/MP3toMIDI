package com.rm.mp3tomidi.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.rm.mp3tomidi.util.ModelProvider
import com.rm.mp3tomidi.util.ModelSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Loads a SoundFont (downloaded default or a user-picked .sf2) into the native Oboe/
 * TinySoundFont engine ([NativeSoundEngine]) and dispatches note on/off for MIDI playback
 * ([com.rm.mp3tomidi.player.MidiPlayer]). All rendering and real-time audio work happens in
 * native code — see native_sound_engine.cpp for why. Lives for the app process lifetime;
 * construct once and share (owned by MainViewModel, same as [com.rm.mp3tomidi.player.Mp3Player]).
 *
 * Adapted from roge-rm/ScaleInKey's SoundEngine.kt, which resolves 4 fixed instrument presets
 * once at load time (Piano/Guitar/Ukulele/Bass, its only playback needs). This one resolves
 * presets lazily and generally instead, keyed by (bank, program) -- a MIDI file can use any of
 * GM's 128 programs across 16 channels, changing which one is "current" per channel via Program
 * Change events during playback, so a fixed preset set doesn't fit here.
 */
class SoundEngine(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var loaded = false

    private val _usingCustomSoundFont = MutableStateFlow(false)
    val usingCustomSoundFont: StateFlow<Boolean> = _usingCustomSoundFont

    // (bank, program) -> resolved TinySoundFont preset index. Cleared whenever a new soundfont
    // is loaded, since preset indices are only meaningful relative to the currently-loaded font.
    private val presetCache = mutableMapOf<Pair<Int, Int>, Int>()

    /** Loads the persisted custom soundfont if one was chosen, otherwise the downloaded default. */
    suspend fun ensureLoaded(onProgress: suspend (fraction: Float) -> Unit) {
        if (loaded) return
        val customUri = prefs.getString(KEY_CUSTOM_SF2_URI, null)
        val loadedCustom = customUri?.let { loadFromUriInternal(Uri.parse(it), persist = false) } ?: false
        if (!loadedCustom) {
            loadDefaultInternal(onProgress)
        }
    }

    /** Resolves (bank, program) to a preset index (cached) and triggers a note. */
    fun noteOn(bank: Int, program: Int, key: Int, velocity: Float) {
        val preset = presetFor(bank, program) ?: return
        NativeSoundEngine.nativeStart()
        NativeSoundEngine.nativeNoteOn(preset, key, velocity)
    }

    fun noteOff(bank: Int, program: Int, key: Int) {
        val preset = presetFor(bank, program) ?: return
        NativeSoundEngine.nativeNoteOff(preset, key)
    }

    private fun presetFor(bank: Int, program: Int): Int? {
        if (!loaded) return null
        return presetCache.getOrPut(bank to program) {
            NativeSoundEngine.nativeGetPresetIndex(bank, program)
        }.takeIf { it >= 0 }
    }

    /** Reads and loads a user-picked .sf2 file, persisting it as the chosen soundfont. */
    fun loadFromUri(uri: Uri, onResult: (Boolean) -> Unit) {
        onResult(loadFromUriInternal(uri, persist = true))
    }

    suspend fun resetToDefault(onProgress: suspend (fraction: Float) -> Unit, onResult: (Boolean) -> Unit) {
        prefs.edit().remove(KEY_CUSTOM_SF2_URI).apply()
        onResult(loadDefaultInternal(onProgress))
    }

    private fun loadFromUriInternal(uri: Uri, persist: Boolean): Boolean {
        return try {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return false
            val ok = loadBytes(bytes)
            if (ok) {
                _usingCustomSoundFont.value = true
                if (persist) {
                    try {
                        appContext.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Could not persist URI permission", e)
                    }
                    prefs.edit().putString(KEY_CUSTOM_SF2_URI, uri.toString()).apply()
                }
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load soundfont from $uri", e)
            false
        }
    }

    private suspend fun loadDefaultInternal(onProgress: suspend (fraction: Float) -> Unit): Boolean {
        return try {
            val file = ModelProvider.ensureAvailable(appContext, DEFAULT_SOUNDFONT_SPEC, { false }, onProgress)
            val ok = loadBytes(file.readBytes())
            _usingCustomSoundFont.value = false
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load default soundfont", e)
            false
        }
    }

    private fun loadBytes(bytes: ByteArray): Boolean {
        if (!NativeSoundEngine.nativeLoadSoundFont(bytes)) {
            Log.e(TAG, "Native engine failed to parse soundfont")
            return false
        }
        presetCache.clear()
        loaded = true
        NativeSoundEngine.nativeStart()
        return true
    }

    fun release() {
        NativeSoundEngine.nativeStop()
    }

    companion object {
        private const val TAG = "SoundEngine"
        private const val PREFS_NAME = "mp3tomidi_sound"
        private const val KEY_CUSTOM_SF2_URI = "custom_sf2_uri"

        // Percussion kits live in bank 128 in FluidR3 and virtually every other GM-compliant
        // soundfont; melodic programs live in bank 0. MIDI channel 9 (0-indexed) is GM's
        // reserved percussion channel.
        const val PERCUSSION_BANK = 128
        const val MELODIC_BANK = 0
        const val PERCUSSION_CHANNEL = 9

        // Real, untrimmed FluidR3 GM -- unlike ScaleInKey's bundled asset (trimmed to 4 presets
        // for its own narrow preview needs), this app needs full 128-program + percussion-kit
        // coverage to play back its own generated MIDI files faithfully. Too large (~151MB) to
        // bundle in the APK, so it's downloaded on first use via ModelProvider, same
        // checksum-verified pattern as the Demucs/YAMNet models.
        val DEFAULT_SOUNDFONT_SPEC = ModelSpec(
            fileName = "FluidR3_GM.sf2",
            downloadUrl = "https://github.com/roge-rm/MP3toMIDI/releases/download/fluidr3-gm-v1/FluidR3_GM.sf2",
            sha256 = "74594e8f4250680adf590507a306655a299935343583256f3b722c48a1bc1cb0",
        )
    }
}
