# Model assets

This source repository does not distribute model weights or other runtime
model assets. The `.gitignore` rules prevent files in
`app/src/main/assets/` with `.ptl` or `.onnx` extensions from being added to
Git.

## Wake-word models

The application currently expects `sgru-1.0.31.ptl` for local wake-word
detection. The older `sgru.ptl` filename may appear in historical development
material but is not loaded by the current service. These wake-word weights are
proprietary to Telefónica and are not included or licensed by this repository.
Obtain them only through an authorized internal channel. They must not be
committed, redistributed or included in a public build without the appropriate
approval.

## Silero VAD

The application expects `silero_vad.onnx` for local voice-activity detection.
It is not included in this repository. If you choose to use it, download the
official Silero VAD model under its upstream MIT License:

- Source: <https://github.com/snakers4/silero-vad/tree/v6.2.1>
- Asset: <https://raw.githubusercontent.com/snakers4/silero-vad/v6.2.1/src/silero_vad/data/silero_vad.onnx>
- SHA-256: `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`
- License: <https://github.com/snakers4/silero-vad/blob/v6.2.1/LICENSE>

Place a verified copy at:

```text
app/src/main/assets/silero_vad.onnx
```

Any distribution that bundles the model must preserve the applicable upstream
copyright and license notice.
