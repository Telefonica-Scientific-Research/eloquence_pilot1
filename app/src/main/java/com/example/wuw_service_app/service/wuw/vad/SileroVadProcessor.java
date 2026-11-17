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

import ai.onnxruntime.OrtException;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the PCM16 audio produced by Android AudioRecord
 * into the 512-sample float frames expected by Silero VAD.
 *
 * This class does NOT perform end-of-speech detection.
 * It only returns Silero speech probabilities.
 */
public class SileroVadProcessor {

    /**
     * At 16 kHz, Silero expects exactly 512 samples
     * for each inference.
     *
     * 512 / 16000 = 0.032 seconds = 32 ms.
     */
    private static final int FRAME_SIZE = SileroVadOnnxModel.FRAME_SIZE;

    private final SileroVadOnnxModel model;

    /**
     * Temporary buffer used to accumulate exactly
     * 512 samples before calling Silero.
     */
    private final short[] frameBuffer = new short[FRAME_SIZE];

    /**
     * Number of valid samples currently stored in frameBuffer.
     */
    private int framePosition = 0;


    public SileroVadProcessor(SileroVadOnnxModel model) {
        this.model = model;
    }


    /**
     * Process audio coming directly from AudioRecord.
     *
     * One AudioRecord.read() may contain:
     * - less than 512 samples
     * - exactly 512 samples
     * - more than 512 samples
     *
     * Therefore this method accumulates samples until a complete
     * Silero frame is available.
     *
     * @param audio      PCM16 mono audio samples
     * @param numberRead number of VALID samples inside audio
     *
     * @return one speech probability for every complete
     *         512-sample frame that was processed.
     */
    public List<Float> process(
            short[] audio,
            int numberRead
    ) throws OrtException {

        List<Float> probabilities = new ArrayList<>();

        if (audio == null) {
            return probabilities;
        }

        if (numberRead <= 0) {
            return probabilities;
        }

        /*
         * Safety check:
         * AudioRecord should never report more valid samples
         * than the array actually contains.
         */
        int validSamples = Math.min(numberRead, audio.length);

        int sourcePosition = 0;

        while (sourcePosition < validSamples) {

            /*
             * Number of samples still needed to complete
             * the current 512-sample Silero frame.
             */
            int samplesNeeded = FRAME_SIZE - framePosition;

            /*
             * Number of samples still available from
             * the current AudioRecord.read().
             */
            int samplesAvailable = validSamples - sourcePosition;

            /*
             * Copy whichever amount is smaller.
             */
            int samplesToCopy = Math.min(samplesNeeded, samplesAvailable);

            System.arraycopy(
                    audio,
                    sourcePosition,
                    frameBuffer,
                    framePosition,
                    samplesToCopy
            );

            sourcePosition += samplesToCopy;
            framePosition += samplesToCopy;

            /*
             * Once we have exactly 512 samples,
             * run Silero VAD.
             */
            if (framePosition == FRAME_SIZE) {

                float[] floatFrame = pcm16ToFloat(frameBuffer);

                float speechProbability = model.predict(floatFrame);

                probabilities.add(speechProbability);

                /*
                 * Start filling the next frame.
                 */
                framePosition = 0;
            }
        }

        return probabilities;
    }


    /**
     * Convert Android PCM16 samples:
     *
     * -32768 ... 32767
     *
     * into floating-point audio approximately:
     *
     * -1.0 ... 1.0
     */
    private float[] pcm16ToFloat(short[] pcm) {

        float[] output = new float[pcm.length];

        for (int i = 0; i < pcm.length; i++) {
            output[i] = pcm[i] / 32768.0f;
        }
        return output;
    }


    /**
     * Reset this processor and the internal Silero state.
     *
     * We will eventually call this when starting
     * a new utterance/session.
     */
    public void reset() {

        framePosition = 0;

        /*
         * Optional, but makes the contents deterministic.
         */
        for (int i = 0; i < frameBuffer.length; i++) {
            frameBuffer[i] = 0;
        }

        model.resetStates();
    }
}
