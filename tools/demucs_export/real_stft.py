"""
Real-valued (no torch.complex64 anywhere) STFT/ISTFT, numerically matching
torch.stft(..., normalized=True, center=True, pad_mode='reflect', return_complex=True)
and torch.istft(..., normalized=True, center=True) exactly, but expressed as
Conv1d/ConvTranspose1d so it exports to ONNX (which the real stft/istft ops don't,
since ONNX has no complex dtype and PyTorch's own aten::stft/view_as_complex
symbolics refuse to produce complex outputs).
"""
import math
import torch
import torch.nn.functional as F


def _analysis_basis(n_fft: int) -> torch.Tensor:
    n_freq = n_fft // 2 + 1
    window = torch.hann_window(n_fft)
    n = torch.arange(n_fft).unsqueeze(0)          # (1, n_fft)
    k = torch.arange(n_freq).unsqueeze(1)          # (n_freq, 1)
    angle = 2 * math.pi * k * n / n_fft
    scale = 1.0 / math.sqrt(n_fft)
    cos_basis = torch.cos(angle) * window.unsqueeze(0) * scale
    sin_basis = -torch.sin(angle) * window.unsqueeze(0) * scale
    basis = torch.cat([cos_basis, sin_basis], dim=0)   # (2*n_freq, n_fft)
    return basis.unsqueeze(1)                          # (2*n_freq, 1, n_fft) for conv1d weight


def real_stft(x: torch.Tensor, n_fft: int, hop_length: int) -> torch.Tensor:
    """x: (..., length) real. Returns (..., n_freq, n_frames, 2) real/imag."""
    *other, length = x.shape
    x = x.reshape(-1, 1, length)
    x = F.pad(x, (n_fft // 2, n_fft // 2), mode="reflect")
    basis = _analysis_basis(n_fft).to(x)
    y = F.conv1d(x, basis, stride=hop_length)          # (batch, 2*n_freq, n_frames)
    n_freq = n_fft // 2 + 1
    real = y[:, :n_freq]
    imag = y[:, n_freq:]
    out = torch.stack([real, imag], dim=-1)            # (batch, n_freq, n_frames, 2)
    return out.reshape(*other, n_freq, out.shape[-2], 2)


def _synthesis_basis(n_fft: int) -> torch.Tensor:
    n_freq = n_fft // 2 + 1
    window = torch.hann_window(n_fft)
    n = torch.arange(n_fft).unsqueeze(0)               # (1, n_fft)
    k = torch.arange(n_freq).unsqueeze(1)               # (n_freq, 1)
    angle = 2 * math.pi * k * n / n_fft
    scale = 1.0 / math.sqrt(n_fft)

    weight = torch.full((n_freq,), 2.0)
    weight[0] = 1.0
    if n_fft % 2 == 0:
        weight[-1] = 1.0

    cos_basis = torch.cos(angle) * scale * weight.unsqueeze(1)
    sin_basis = -torch.sin(angle) * scale * weight.unsqueeze(1)
    # multiply the reconstructed (window * segment) frame by the window again (WOLA)
    cos_basis = cos_basis * window.unsqueeze(0)
    sin_basis = sin_basis * window.unsqueeze(0)
    return cos_basis.unsqueeze(1), sin_basis.unsqueeze(1)  # each (n_freq, 1, n_fft)


def real_istft(z: torch.Tensor, n_fft: int, hop_length: int, length: int) -> torch.Tensor:
    """z: (..., n_freq, n_frames, 2) real/imag. Returns (..., length) real."""
    *other, n_freq, n_frames, _ = z.shape
    z = z.reshape(-1, n_freq, n_frames, 2)
    real = z[..., 0]   # (batch, n_freq, n_frames) == (batch, C_in, L) for conv_transpose1d
    imag = z[..., 1]

    cos_basis, sin_basis = _synthesis_basis(n_fft)
    cos_basis = cos_basis.to(z)
    sin_basis = sin_basis.to(z)

    frames = F.conv_transpose1d(real, cos_basis, stride=hop_length) + \
        F.conv_transpose1d(imag, sin_basis, stride=hop_length)
    # frames: (batch, 1, padded_len)

    window = torch.hann_window(n_fft).to(z)
    win_sq_conv_weight = (window * window).reshape(1, 1, n_fft)
    norm_input = torch.ones(1, 1, n_frames, device=z.device, dtype=z.dtype)
    norm = F.conv_transpose1d(norm_input, win_sq_conv_weight, stride=hop_length)

    frames = frames / (norm + 1e-11)

    pad = n_fft // 2
    out = frames[..., pad:pad + length]
    return out.reshape(*other, length)
