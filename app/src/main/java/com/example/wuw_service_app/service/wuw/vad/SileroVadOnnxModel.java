package com.example.wuw_service_app.service.wuw.vad;

import android.content.Context;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Wrapper around the Silero VAD ONNX model.
 *
 * This class:
 *  - loads silero_vad.onnx from Android assets
 *  - receives one 16 kHz audio frame
 *  - runs Silero VAD
 *  - returns the probability that the frame contains speech
 *  - keeps the recurrent Silero state between consecutive frames
 */
public class SileroVadOnnxModel implements AutoCloseable {

    /**
     * This implementation only supports 16 kHz audio.
     */
    public static final int SAMPLE_RATE = 16000;

    /**
     * Silero expects 512 samples per inference at 16 kHz.
     *
     * 512 / 16000 = 0.032 seconds = 32 ms.
     */
    public static final int FRAME_SIZE = 512;

    /**
     * Number of samples from the previous frame
     * that Silero keeps as context.
     */
    private static final int CONTEXT_SIZE = 64;


    private final OrtEnvironment environment;
    private final OrtSession.SessionOptions sessionOptions;
    private final OrtSession session;


    /**
     * Recurrent state used internally by Silero.
     *
     * Shape:
     * [2][1][128]
     */
    private float[][][] state;


    /**
     * Small amount of audio from the previous frame
     * used as context for the next inference.
     *
     * At 16 kHz this contains 64 samples.
     */
    private float[][] context;


    /**
     * Load silero_vad.onnx from:
     *
     * src/main/assets/silero_vad.onnx
     */
    public SileroVadOnnxModel(Context context)
            throws IOException, OrtException {

        environment = OrtEnvironment.getEnvironment();

        sessionOptions = new OrtSession.SessionOptions();

        // Silero is small. One thread is enough for now.
        sessionOptions.setInterOpNumThreads(1);
        sessionOptions.setIntraOpNumThreads(1);

        byte[] modelBytes =
                readAsset(
                        context,
                        "silero_vad.onnx"
                );

        session =
                environment.createSession(
                        modelBytes,
                        sessionOptions
                );

        resetStates();
    }


    /**
     * Run VAD on one 16 kHz audio frame.
     *
     * @param audio exactly 512 mono floating-point PCM samples,
     *              approximately in the range [-1, 1]
     *
     * @return probability of speech, normally between 0 and 1
     */
    public float predict(float[] audio)
            throws OrtException {

        validateInput(audio);


        /*
         * First inference:
         * initialise the previous-frame context with zeros.
         */
        if (context == null) {
            context = new float[1][CONTEXT_SIZE];
        }


        /*
         * Silero receives:
         *
         * [64 samples of previous context]
         * +
         * [512 samples of current audio]
         *
         * = 576 samples
         */
        float[][] input =
                new float[1][CONTEXT_SIZE + audio.length];


        /*
         * Copy previous context.
         */
        System.arraycopy(
                context[0],
                0,
                input[0],
                0,
                CONTEXT_SIZE
        );


        /*
         * Copy the current 512-sample audio frame.
         */
        System.arraycopy(
                audio,
                0,
                input[0],
                CONTEXT_SIZE,
                audio.length
        );


        OnnxTensor inputTensor = null;
        OnnxTensor stateTensor = null;
        OnnxTensor sampleRateTensor = null;
        OrtSession.Result outputs = null;


        try {

            /*
             * Convert Java arrays into ONNX tensors.
             */
            inputTensor =
                    OnnxTensor.createTensor(
                            environment,
                            input
                    );

            stateTensor =
                    OnnxTensor.createTensor(
                            environment,
                            state
                    );

            /*
             * The Silero ONNX model requires the sample rate
             * as an input tensor.
             *
             * Our implementation always uses 16000 Hz.
             */
            sampleRateTensor =
                    OnnxTensor.createTensor(
                            environment,
                            new long[]{SAMPLE_RATE}
                    );


            /*
             * These input names come from the
             * Silero ONNX model.
             */
            Map<String, OnnxTensor> inputs =
                    new HashMap<>();

            inputs.put("input", inputTensor);
            inputs.put("sr", sampleRateTensor);
            inputs.put("state", stateTensor);


            /*
             * Run the neural network.
             */
            outputs =
                    session.run(inputs);


            /*
             * First output:
             * speech probability.
             *
             * Shape: [1][1]
             */
            float[][] probability =
                    (float[][]) outputs
                            .get(0)
                            .getValue();


            /*
             * Second output:
             * updated recurrent state.
             *
             * This must be preserved for the next frame.
             */
            state =
                    (float[][][]) outputs
                            .get(1)
                            .getValue();


            /*
             * Save the final 64 samples of the current
             * frame as context for the next inference.
             */
            context =
                    new float[1][CONTEXT_SIZE];

            System.arraycopy(
                    audio,
                    audio.length - CONTEXT_SIZE,
                    context[0],
                    0,
                    CONTEXT_SIZE
            );


            return probability[0][0];

        } finally {

            /*
             * ONNX tensors use native memory,
             * so always release them.
             */
            if (inputTensor != null) {
                inputTensor.close();
            }

            if (stateTensor != null) {
                stateTensor.close();
            }

            if (sampleRateTensor != null) {
                sampleRateTensor.close();
            }

            if (outputs != null) {
                outputs.close();
            }
        }
    }


    /**
     * Reset Silero's recurrent memory and audio context.
     *
     * Call this when starting a new user query.
     */
    public void resetStates() {

        state =
                new float[2][1][128];

        context = null;
    }


    /**
     * Validate that the input frame has exactly
     * the size required by our 16 kHz Silero configuration.
     */
    private void validateInput(float[] audio) {

        if (audio == null) {
            throw new IllegalArgumentException(
                    "Audio frame cannot be null."
            );
        }

        if (audio.length != FRAME_SIZE) {

            throw new IllegalArgumentException(
                    "Silero VAD requires exactly "
                            + FRAME_SIZE
                            + " samples at "
                            + SAMPLE_RATE
                            + " Hz. Received: "
                            + audio.length
            );
        }
    }


    /**
     * Read an Android asset completely into memory.
     *
     * assets/silero_vad.onnx
     *          ↓
     *        byte[]
     *          ↓
     *     ONNX Runtime
     */
    private static byte[] readAsset(
            Context context,
            String assetName
    ) throws IOException {

        try (
                InputStream inputStream =
                        context.getAssets().open(assetName);

                ByteArrayOutputStream outputStream =
                        new ByteArrayOutputStream()
        ) {

            byte[] buffer =
                    new byte[8192];

            int bytesRead;

            while (
                    (bytesRead =
                            inputStream.read(buffer))
                            != -1
            ) {

                outputStream.write(
                        buffer,
                        0,
                        bytesRead
                );
            }

            return outputStream.toByteArray();
        }
    }


    /**
     * Release ONNX Runtime resources.
     */
    @Override
    public void close()
            throws OrtException {

        session.close();
        sessionOptions.close();
    }
}