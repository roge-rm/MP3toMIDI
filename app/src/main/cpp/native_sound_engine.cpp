// Native audio engine: Oboe (real-time output stream) + TinySoundFont (tsf.h, vendored
// directly — see tsf.h's own header comment for its MIT license) for SF2 synthesis.
//
// Everything real-time-sensitive (rendering, applying note-on/off) happens here, entirely in
// native code, on Oboe's own high-priority audio callback thread. The Kotlin side
// (SoundEngine.kt / NativeSoundEngine.kt) never touches the render path — it just calls
// noteOn/noteOff/loadSoundFont and Oboe/this file does the rest.
//
// Ported from roge-rm/ScaleInKey's native_sound_engine.cpp (same author/org, same Oboe +
// TinySoundFont approach) — its loadSoundFont/getPresetIndex/noteOn/noteOff API turned out to
// already be exactly general enough for a full MIDI sequencer's needs, not just single-note
// preview, so this file is a verbatim port (renamed project/package identifiers only). The real
// MIDI-sequencing logic (channel/program state, event timing) lives entirely in Kotlin
// (see MidiPlayer.kt) and drives this same low-level API.
//
// Threading model, and why it looks like this:
//   - tsf_note_on / tsf_render_float share the same internal voice-pool state in tsf.h, and
//     tsf.h has NO internal locking. Calling tsf_note_on from an arbitrary JNI-calling thread
//     while tsf_render_float runs concurrently on the audio callback thread is a genuine data
//     race (undefined behavior), not a hypothetical one — this is the leading suspect for
//     intermittent corrupted/buzzy playback seen during development with a previous
//     (different-library) implementation that didn't synchronize the two.
//   - The fix: note-on/off requests are pushed onto a lock-free single-consumer queue from
//     whatever thread calls noteOn()/noteOff(), and only ever *applied* (tsf_note_on/off
//     actually called) from inside the audio callback itself, right before rendering. So
//     tsf_note_on/tsf_render_float only ever run on one thread: the audio thread.
//   - tsf_note_on is real-time-safe (bounded time, no allocation) *provided*
//     tsf_set_max_voices() has already been called — verified by reading tsf.h's source:
//     without it, running out of pre-allocated voices triggers a realloc, which would be
//     unsafe inside an audio callback. This engine always calls tsf_set_max_voices()
//     immediately after loading.
//   - Loading a new soundfont (rare, user-initiated) is handled by fully stopping the Oboe
//     stream (which blocks until the callback thread is confirmed idle), swapping the tsf*
//     pointer, closing the old one, then restarting — simpler and safer than trying to
//     lock-free-swap a resource that's mutated this rarely.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

#include <oboe/Oboe.h>

#define TSF_IMPLEMENTATION
#include "tsf.h"

#define LOG_TAG "MP3toMIDIAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kSampleRate = 44100;
constexpr int kMaxVoices = 32;
constexpr int kQueueCapacity = 64; // generous vs. realistic note-trigger rate from taps

struct NoteCommand {
    bool isNoteOn;
    int preset;
    int key;
    float velocity;
};

// Multi-producer (any calling thread; producers are serialized by pushMutex_, which is never
// touched by the audio thread), single-consumer (the audio callback, lock-free) ring buffer.
class NoteCommandQueue {
public:
    bool push(const NoteCommand &cmd) {
        std::lock_guard<std::mutex> guard(pushMutex_);
        int tail = tail_.load(std::memory_order_relaxed);
        int nextTail = (tail + 1) % kQueueCapacity;
        if (nextTail == head_.load(std::memory_order_acquire)) {
            return false; // full — shouldn't happen at realistic tap rates; drop rather than block
        }
        buffer_[tail] = cmd;
        tail_.store(nextTail, std::memory_order_release);
        return true;
    }

    // Only ever called from the audio callback thread.
    bool pop(NoteCommand &out) {
        int head = head_.load(std::memory_order_relaxed);
        if (head == tail_.load(std::memory_order_acquire)) {
            return false; // empty
        }
        out = buffer_[head];
        head_.store((head + 1) % kQueueCapacity, std::memory_order_release);
        return true;
    }

private:
    NoteCommand buffer_[kQueueCapacity]{};
    std::atomic<int> head_{0};
    std::atomic<int> tail_{0};
    std::mutex pushMutex_;
};

class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    bool loadSoundFont(const void *data, int size) {
        tsf *newFont = tsf_load_memory(data, size);
        if (!newFont) {
            LOGE("tsf_load_memory failed");
            return false;
        }
        tsf_set_output(newFont, TSF_STEREO_INTERLEAVED, kSampleRate, 0.0f);
        tsf_set_max_voices(newFont, kMaxVoices);

        std::lock_guard<std::mutex> guard(streamMutex_);
        bool wasRunning = (stream_ != nullptr);
        if (wasRunning) stopStreamLocked();

        tsf *old = font_.exchange(newFont, std::memory_order_acq_rel);
        if (old) tsf_close(old);

        if (wasRunning) startStreamLocked();
        return true;
    }

    int getPresetIndex(int bank, int program) const {
        tsf *f = font_.load(std::memory_order_acquire);
        return f ? tsf_get_presetindex(f, bank, program) : -1;
    }

    void noteOn(int preset, int key, float velocity) {
        queue_.push(NoteCommand{true, preset, key, velocity});
    }

    void noteOff(int preset, int key) {
        queue_.push(NoteCommand{false, preset, key, 0.0f});
    }

    bool start() {
        std::lock_guard<std::mutex> guard(streamMutex_);
        return startStreamLocked();
    }

    void stop() {
        std::lock_guard<std::mutex> guard(streamMutex_);
        stopStreamLocked();
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream * /*stream*/, void *audioData, int32_t numFrames) override {
        tsf *f = font_.load(std::memory_order_acquire);

        NoteCommand cmd;
        while (queue_.pop(cmd)) {
            if (!f) continue;
            if (cmd.isNoteOn) {
                tsf_note_on(f, cmd.preset, cmd.key, cmd.velocity);
            } else {
                tsf_note_off(f, cmd.preset, cmd.key);
            }
        }

        auto *out = static_cast<float *>(audioData);
        if (f) {
            tsf_render_float(f, out, numFrames, 0);
        } else {
            std::memset(out, 0, sizeof(float) * numFrames * 2);
        }

        // Defensive: never forward a non-finite or implausibly loud sample to hardware,
        // regardless of cause. Audio here should never realistically exceed roughly +/-4.0.
        int total = numFrames * 2;
        for (int i = 0; i < total; i++) {
            float v = out[i];
            if (!std::isfinite(v) || v > 4.0f || v < -4.0f) out[i] = 0.0f;
        }
        return oboe::DataCallbackResult::Continue;
    }

    bool onError(oboe::AudioStream * /*stream*/, oboe::Result error) override {
        LOGW("Oboe stream error: %s", oboe::convertToText(error));
        return false; // false = let Oboe stop+close it; we reopen in onErrorAfterClose
    }

    void onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) override {
        LOGW("Oboe stream closed after error (%s); reopening", oboe::convertToText(error));
        std::lock_guard<std::mutex> guard(streamMutex_);
        stream_.reset();
        startStreamLocked();
    }

private:
    // Caller must hold streamMutex_.
    bool startStreamLocked() {
        if (stream_) return true;
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(2)
                ->setSampleRate(kSampleRate)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setUsage(oboe::Usage::Media)
                ->setContentType(oboe::ContentType::Music)
                ->setDataCallback(this)
                ->setErrorCallback(this);
        oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            LOGE("openStream failed: %s", oboe::convertToText(result));
            stream_.reset();
            return false;
        }
        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("requestStart failed: %s", oboe::convertToText(result));
            stream_->close();
            stream_.reset();
            return false;
        }
        return true;
    }

    // Caller must hold streamMutex_.
    void stopStreamLocked() {
        if (!stream_) return;
        stream_->stop(); // blocks until the callback thread is confirmed idle
        stream_->close();
        stream_.reset();
    }

    std::atomic<tsf *> font_{nullptr};
    NoteCommandQueue queue_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex streamMutex_;
};

Engine &engine() {
    static Engine instance;
    return instance;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_rm_mp3tomidi_audio_NativeSoundEngine_nativeLoadSoundFont(JNIEnv *env, jobject /*thiz*/, jbyteArray bytes) {
    jsize len = env->GetArrayLength(bytes);
    std::vector<jbyte> buffer(static_cast<size_t>(len));
    env->GetByteArrayRegion(bytes, 0, len, buffer.data());
    return engine().loadSoundFont(buffer.data(), static_cast<int>(len)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_rm_mp3tomidi_audio_NativeSoundEngine_nativeGetPresetIndex(JNIEnv * /*env*/, jobject /*thiz*/, jint bank, jint program) {
    return engine().getPresetIndex(bank, program);
}

JNIEXPORT jboolean JNICALL
Java_com_rm_mp3tomidi_audio_NativeSoundEngine_nativeStart(JNIEnv * /*env*/, jobject /*thiz*/) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_rm_mp3tomidi_audio_NativeSoundEngine_nativeStop(JNIEnv * /*env*/, jobject /*thiz*/) {
    engine().stop();
}

JNIEXPORT void JNICALL
Java_com_rm_mp3tomidi_audio_NativeSoundEngine_nativeNoteOn(JNIEnv * /*env*/, jobject /*thiz*/, jint preset, jint key, jfloat velocity) {
    engine().noteOn(preset, key, velocity);
}

JNIEXPORT void JNICALL
Java_com_rm_mp3tomidi_audio_NativeSoundEngine_nativeNoteOff(JNIEnv * /*env*/, jobject /*thiz*/, jint preset, jint key) {
    engine().noteOff(preset, key);
}

} // extern "C"
