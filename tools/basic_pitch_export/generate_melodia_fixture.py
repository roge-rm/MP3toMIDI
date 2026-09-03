"""
Generates a fixture that isolates the melodia trick specifically: sustained frame energy on a
bin with NO onset anywhere (neither predicted nor inferred from frame differences -- flat energy
from frame 0 produces zero frame-to-frame diff, so get_infered_onsets never picks it up either),
alongside the same three bins from generate_decoder_fixture.py so this also verifies melodia
trick correctly leaves already-claimed onset-loop regions alone. Run with the real
basic_pitch.note_creation.output_to_notes_polyphonic (melodia_trick=True) for ground truth.
"""
import numpy as np

from basic_pitch.note_creation import output_to_notes_polyphonic

np.random.seed(0)

N_TIMES = 40
N_FREQS = 88

frames = np.zeros((N_TIMES, N_FREQS), dtype=np.float32)
onsets = np.zeros((N_TIMES, N_FREQS), dtype=np.float32)

# Same as generate_decoder_fixture.py's Note A/B, so the melodia-trick pass has real
# already-claimed regions to correctly avoid re-picking-up.
onsets[2, 10] = 0.9
frames[2:15, 10] = 0.6
onsets[5, 20] = 0.8
frames[5:19, 20] = 0.5

# Melodia-trick-only note: flat sustained energy from frame 0 on bin 50, well above
# frame_thresh=0.3, no onset anywhere (predicted or inferred -- constant energy has zero
# frame-to-frame diff, so get_infered_onsets can't surface it either). Long enough
# (30 frames) to clear min_note_len=11 after growing bidirectionally from its peak.
frames[0:30, 50] = 0.6

onset_thresh = 0.5
frame_thresh = 0.3
min_note_len = 11

notes = output_to_notes_polyphonic(
    frames.copy(),
    onsets.copy(),
    onset_thresh=onset_thresh,
    frame_thresh=frame_thresh,
    min_note_len=min_note_len,
    infer_onsets=True,
    max_freq=None,
    min_freq=None,
    melodia_trick=True,
)

print("notes (start_frame, end_frame, midi_pitch, amplitude):")
for n in sorted(notes):
    print(f"  {n}")
