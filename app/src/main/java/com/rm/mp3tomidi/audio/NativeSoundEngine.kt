package com.rm.mp3tomidi.audio

/**
 * JNI bridge to the native (C++) Oboe + TinySoundFont engine in
 * app/src/main/cpp/native_sound_engine.cpp. All real-time-sensitive work — rendering, and
 * actually applying note-on/off — happens entirely in native code on Oboe's own audio
 * callback thread; these calls just enqueue requests or control stream lifecycle.
 */
internal object NativeSoundEngine {
    init {
        System.loadLibrary("mp3tomidiaudio")
    }

    external fun nativeLoadSoundFont(bytes: ByteArray): Boolean
    external fun nativeGetPresetIndex(bank: Int, program: Int): Int
    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativeNoteOn(preset: Int, key: Int, velocity: Float)
    external fun nativeNoteOff(preset: Int, key: Int)
}
