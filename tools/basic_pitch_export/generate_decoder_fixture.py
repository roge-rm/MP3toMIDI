"""
Generates a small, deterministic onset/frame activation matrix and runs it through basic_pitch's
actual note_creation.output_to_notes_polyphonic + get_infered_onsets + model_frames_to_time
(melodia_trick disabled, to match BasicPitchNoteDecoder.kt's scope), printing both the inputs and
expected outputs as Kotlin source so BasicPitchNoteDecoderTest.kt can assert against real
ground truth rather than a hand-derived expectation.
"""
import numpy as np

from basic_pitch.note_creation import get_infered_onsets, output_to_notes_polyphonic, model_frames_to_time

np.random.seed(0)

N_TIMES = 40
N_FREQS = 88

frames = np.zeros((N_TIMES, N_FREQS), dtype=np.float32)
onsets = np.zeros((N_TIMES, N_FREQS), dtype=np.float32)

# Note A: clear onset + sustained frame energy on bin 10, frames 2-14
onsets[2, 10] = 0.9
frames[2:15, 10] = 0.6

# Note B: shorter note (still above min_note_len=11 by a couple frames) on bin 20, frames 5-18
onsets[5, 20] = 0.8
frames[5:19, 20] = 0.5

# Too-short note on bin 30: should be dropped (min_note_len=11)
onsets[8, 30] = 0.95
frames[8:14, 30] = 0.7

# Sub-threshold onset on bin 40: should be ignored (onset_thresh=0.5)
onsets[10, 40] = 0.2
frames[10:25, 40] = 0.6

onset_thresh = 0.5
frame_thresh = 0.3
min_note_len = 11

onsets_inferred = get_infered_onsets(onsets.copy(), frames.copy())

notes = output_to_notes_polyphonic(
    frames.copy(),
    onsets.copy(),
    onset_thresh=onset_thresh,
    frame_thresh=frame_thresh,
    min_note_len=min_note_len,
    infer_onsets=True,
    max_freq=None,
    min_freq=None,
    melodia_trick=False,
)

print("frames =", np.array2string(frames, separator=", ", threshold=100000).replace("\n", ""))
print()
print("onsets =", np.array2string(onsets, separator=", ", threshold=100000).replace("\n", ""))
print()
print("notes (start_frame, end_frame, midi_pitch, amplitude):")
for n in sorted(notes):
    print(f"  {n}")

times = model_frames_to_time(N_TIMES)
print()
print("model_frames_to_time(40) =", np.array2string(times, separator=", ", threshold=100000).replace("\n", ""))
