"""
ONNX's legacy TorchScript-based exporter has no complex dtype: it refuses
aten::stft when return_complex=True, and aten::view_as_complex is simply
unimplemented. HTDemucs's _spec/_ispec/_magnitude/_mask methods construct and
consume genuine torch.complex64 tensors throughout, so none of that survives
export as written.

This replaces those four methods (via instance-level monkeypatching, so the
frozen pretrained weights are untouched) with equivalents built on real_stft.py's
Conv1d/ConvTranspose1d STFT, which is numerically within float32-accumulation
tolerance of torch.stft/istft (see verify_real_stft.py) but never produces a
complex-dtype tensor.
"""
import math
import types

import torch
import torch.nn.functional as F
from demucs.hdemucs import pad1d

from real_stft import real_stft, real_istft


def _spec_real(self, x):
    hl = self.hop_length
    nfft = self.nfft
    assert hl == nfft // 4
    le = int(math.ceil(x.shape[-1] / hl))
    pad = hl // 2 * 3
    x = pad1d(x, (pad, pad + le * hl - x.shape[-1]), mode="reflect")
    z = real_stft(x, nfft, hl)[..., :-1, :, :]
    assert z.shape[-2] == le + 4, (z.shape, x.shape, le)
    z = z[..., 2:2 + le, :]
    return z


def _ispec_real(self, z, length=None, scale=0):
    hl = self.hop_length // (4 ** scale)
    z = F.pad(z, (0, 0, 0, 0, 0, 1))
    z = F.pad(z, (0, 0, 2, 2))
    pad = hl // 2 * 3
    le = hl * int(math.ceil(length / hl)) + 2 * pad
    x = real_istft(z, self.nfft, hl, length=le)
    x = x[..., pad: pad + length]
    return x


def _magnitude_real(self, z):
    assert self.cac, "only the complex-as-channels path is patched"
    B, C, Fr, T, _ = z.shape
    m = z.permute(0, 1, 4, 2, 3).reshape(B, C * 2, Fr, T)
    return m


def _mask_real(self, z, m):
    assert self.cac, "only the complex-as-channels path is patched"
    B, S, C2, Fr, T = m.shape
    out = m.view(B, S, -1, 2, Fr, T).permute(0, 1, 2, 4, 5, 3)
    return out.contiguous()


def patch_htdemucs_for_onnx_export(model):
    model._spec = types.MethodType(_spec_real, model)
    model._ispec = types.MethodType(_ispec_real, model)
    model._magnitude = types.MethodType(_magnitude_real, model)
    model._mask = types.MethodType(_mask_real, model)
    return model
