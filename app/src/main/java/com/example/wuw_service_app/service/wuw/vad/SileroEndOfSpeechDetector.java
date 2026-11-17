// © 2026 Telefónica Innovación Digital S.L.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.example.wuw_service_app.service.wuw.vad;

/**
 * Decides when the user has stopped speaking based on
 * consecutive Silero VAD probabilities.
 *
 * This class does not run the ONNX model.
 * It only receives probabilities produced by SileroVadProcessor.
 */
public class SileroEndOfSpeechDetector {

    /**
     * Silero produces one probability every 512 samples.
     * At 16 kHz:
     * 512 / 16000 = 0.032 seconds = 32 ms
     */
    private static final int FRAME_DURATION_MS = 32;

    private final float speechThreshold;

    private final int requiredSilentFrames;

    /**
     * Number of consecutive frames currently considered silence.
     */
    private int silentFrames = 0;


    /**
     * @param speechThreshold      probability above which we consider
     *                             the frame to contain speech
     *
     * @param silenceDurationMs    how long silence must continue before
     *                             the utterance is considered finished
     */
    public SileroEndOfSpeechDetector(
            float speechThreshold,
            long silenceDurationMs
    ) {

        this.speechThreshold = speechThreshold;

        this.requiredSilentFrames =
                (int) Math.ceil(
                        silenceDurationMs /
                                (double) FRAME_DURATION_MS
                );
    }


    /**
     * Process one Silero speech probability.
     * @return true when enough consecutive silence has been detected.
     */
    public boolean process(float speechProbability) {

        if (speechProbability > speechThreshold) {

            // Speech detected → restart the silence counter.
            silentFrames = 0;

        } else {

            // No speech → continue counting silence.
            silentFrames++;
        }

        return silentFrames >= requiredSilentFrames;
    }
}
