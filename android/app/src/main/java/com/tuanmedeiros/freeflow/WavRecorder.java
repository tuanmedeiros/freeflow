package com.tuanmedeiros.freeflow;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Captures microphone PCM and writes a standard 16 kHz mono PCM WAV file. */
final class WavRecorder {
    private static final int SAMPLE_RATE = 16_000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;

    private final File directory;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private BufferedOutputStream output;
    private File outputFile;
    private volatile boolean recording;
    private volatile IOException captureError;
    private long dataBytes;

    WavRecorder(File directory) {
        this.directory = directory;
    }

    synchronized File start() throws IOException {
        if (recording) {
            throw new IOException("A recording is already in progress");
        }

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) {
            throw new IOException("This device does not provide a usable microphone format");
        }

        int bufferSize = Math.max(minBuffer * 2, SAMPLE_RATE / 2);
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );
        } catch (SecurityException exception) {
            throw new IOException("Microphone permission was denied", exception);
        }
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            throw new IOException("The microphone could not be initialized");
        }

        outputFile = File.createTempFile("freeflow-recording-", ".wav", directory);
        output = new BufferedOutputStream(new FileOutputStream(outputFile));
        writeHeader(output, 0);
        dataBytes = 0;
        captureError = null;
        recording = true;
        try {
            audioRecord.startRecording();
        } catch (IllegalStateException exception) {
            recording = false;
            output.close();
            outputFile.delete();
            audioRecord.release();
            audioRecord = null;
            throw new IOException("The microphone could not start", exception);
        }

        final int samplesPerRead = Math.max(256, bufferSize / 2);
        captureThread = new Thread(() -> capture(samplesPerRead), "freeflow-audio-capture");
        captureThread.start();
        return outputFile;
    }

    synchronized File stop() throws IOException {
        if (!recording && captureThread == null) {
            throw new IOException("No recording is active");
        }
        recording = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
                // The capture thread still gets joined and the partial file is usable.
            }
        }

        Thread thread = captureThread;
        if (thread != null) {
            try {
                thread.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        captureThread = null;
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        if (output != null) {
            output.flush();
            output.close();
            output = null;
        }

        if (captureError != null) {
            File failedFile = outputFile;
            outputFile = null;
            if (failedFile != null) {
                failedFile.delete();
            }
            throw captureError;
        }
        if (outputFile == null || dataBytes == 0) {
            File emptyFile = outputFile;
            outputFile = null;
            if (emptyFile != null) {
                emptyFile.delete();
            }
            throw new IOException("No audio was captured");
        }

        File completedFile = outputFile;
        patchHeader(completedFile, dataBytes);
        outputFile = null;
        return completedFile;
    }

    private void capture(int samplesPerRead) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] samples = new short[samplesPerRead];
        try {
            while (recording) {
                int count = audioRecord.read(samples, 0, samples.length);
                if (count == AudioRecord.ERROR_INVALID_OPERATION || count == AudioRecord.ERROR_BAD_VALUE
                        || count == AudioRecord.ERROR_DEAD_OBJECT) {
                    throw new IOException("The microphone stopped while recording");
                }
                if (count <= 0) {
                    continue;
                }

                byte[] bytes = new byte[count * 2];
                for (int index = 0; index < count; index++) {
                    short sample = samples[index];
                    bytes[index * 2] = (byte) (sample & 0xff);
                    bytes[index * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
                }
                synchronized (this) {
                    if (output != null) {
                        output.write(bytes);
                        dataBytes += bytes.length;
                    }
                }
            }
        } catch (IOException exception) {
            captureError = exception;
            recording = false;
        }
    }

    private static void writeHeader(java.io.OutputStream stream, long dataLength) throws IOException {
        writeAscii(stream, "RIFF");
        writeIntLE(stream, (int) (36 + dataLength));
        writeAscii(stream, "WAVE");
        writeAscii(stream, "fmt ");
        writeIntLE(stream, 16);
        writeShortLE(stream, (short) 1);
        writeShortLE(stream, (short) CHANNELS);
        writeIntLE(stream, SAMPLE_RATE);
        writeIntLE(stream, SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8);
        writeShortLE(stream, (short) (CHANNELS * BITS_PER_SAMPLE / 8));
        writeShortLE(stream, (short) BITS_PER_SAMPLE);
        writeAscii(stream, "data");
        writeIntLE(stream, (int) dataLength);
    }

    private static void patchHeader(File file, long dataLength) throws IOException {
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
            randomAccessFile.seek(4);
            writeIntLE(randomAccessFile, (int) (36 + dataLength));
            randomAccessFile.seek(40);
            writeIntLE(randomAccessFile, (int) dataLength);
        }
    }

    private static void writeAscii(java.io.OutputStream stream, String value) throws IOException {
        stream.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeIntLE(java.io.OutputStream stream, int value) throws IOException {
        stream.write(value & 0xff);
        stream.write((value >>> 8) & 0xff);
        stream.write((value >>> 16) & 0xff);
        stream.write((value >>> 24) & 0xff);
    }

    private static void writeShortLE(java.io.OutputStream stream, short value) throws IOException {
        stream.write(value & 0xff);
        stream.write((value >>> 8) & 0xff);
    }

    private static void writeIntLE(RandomAccessFile file, int value) throws IOException {
        file.write(value & 0xff);
        file.write((value >>> 8) & 0xff);
        file.write((value >>> 16) & 0xff);
        file.write((value >>> 24) & 0xff);
    }
}
