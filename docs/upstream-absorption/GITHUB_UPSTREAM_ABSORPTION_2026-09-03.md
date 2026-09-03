# Upstream Absorption — Vibra — 2026-09-03

## RustAudio/cpal
Preferred upstream to evaluate for low-level Rust cross-platform audio I/O.
- Encapsulate device enumeration/open/stream/recovery behind a project interface.
- Keep real-time callbacks allocation-free and non-blocking.
- Test Bluetooth/USB/built-in devices, sample-rate changes and device loss.

## RustAudio/rodio
Use only where higher-level playback simplifies the app without compromising latency/control. Do not force it into real-time DSP paths that need CPAL-level control.

## sherpa-onnx / whisper.cpp (conditional)
If Vibra adds voice-aware features, implement through an offline/background speech backend and never inside the audio callback.

## Acceptance gates
- No regression in latency or glitch rate.
- Device switching is recoverable.
- Core app stays fully local/free.
- New speech/model assets, if any, are separately commercial-license reviewed.