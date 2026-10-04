package com.tuanmedeiros.freeflow;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android entry point for microphone dictation and OpenAI-compatible transcription. */
public final class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 42;
    private static final String PREFS = "freeflow-settings";
    private static final String BASE_URL = "base-url";
    private static final String TRANSCRIPTION_MODEL = "transcription-model";
    private static final String CLEANUP_MODEL = "cleanup-model";
    private static final String CLEANUP_ENABLED = "cleanup-enabled";

    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private EditText apiKeyField;
    private EditText baseUrlField;
    private EditText transcriptionModelField;
    private EditText cleanupModelField;
    private CheckBox cleanupCheckBox;
    private Button recordButton;
    private Button copyButton;
    private Button shareButton;
    private TextView statusView;
    private TextView transcriptView;
    private WavRecorder recorder;
    private boolean recording;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(248, 249, 252));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildContentView());
    }

    private View buildContentView() {
        int padding = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, dp(12), padding, dp(24));

        TextView title = new TextView(this);
        title.setText("FreeFlow");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(25, 25, 35));
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Voice dictation for Android");
        subtitle.setTextSize(16);
        subtitle.setTextColor(Color.rgb(90, 92, 105));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.bottomMargin = dp(18);
        content.addView(subtitle, subtitleParams);

        content.addView(label("Provider API key"));
        apiKeyField = editText("Required for transcription", true);
        apiKeyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyField.setTransformationMethod(PasswordTransformationMethod.getInstance());
        apiKeyField.setText(SecretStore.read(this));
        content.addView(apiKeyField, fieldParams());

        content.addView(label("OpenAI-compatible base URL"));
        baseUrlField = editText(ProviderClient.defaultBaseUrl(), false);
        baseUrlField.setText(preferences.getString(BASE_URL, ProviderClient.defaultBaseUrl()));
        content.addView(baseUrlField, fieldParams());

        content.addView(label("Transcription model"));
        transcriptionModelField = editText(ProviderClient.defaultTranscriptionModel(), false);
        transcriptionModelField.setText(preferences.getString(TRANSCRIPTION_MODEL, ProviderClient.defaultTranscriptionModel()));
        content.addView(transcriptionModelField, fieldParams());

        cleanupCheckBox = new CheckBox(this);
        cleanupCheckBox.setText("Clean up transcript with an LLM");
        cleanupCheckBox.setTextSize(16);
        cleanupCheckBox.setChecked(preferences.getBoolean(CLEANUP_ENABLED, true));
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, -2);
        checkParams.topMargin = dp(4);
        content.addView(cleanupCheckBox, checkParams);

        content.addView(label("Cleanup model"));
        cleanupModelField = editText(ProviderClient.defaultCleanupModel(), false);
        cleanupModelField.setText(preferences.getString(CLEANUP_MODEL, ProviderClient.defaultCleanupModel()));
        content.addView(cleanupModelField, fieldParams());

        recordButton = new Button(this);
        recordButton.setText("Start dictation");
        recordButton.setTextSize(16);
        recordButton.setOnClickListener(view -> onRecordClicked());
        LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(-1, dp(54));
        recordParams.topMargin = dp(14);
        content.addView(recordButton, recordParams);

        statusView = new TextView(this);
        statusView.setText("Ready. Add your provider key, then tap Start dictation.");
        statusView.setTextColor(Color.rgb(82, 84, 98));
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(10), 0, dp(10));
        content.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        TextView transcriptLabel = label("Transcript");
        content.addView(transcriptLabel);
        transcriptView = new TextView(this);
        transcriptView.setText("Your transcript will appear here.");
        transcriptView.setTextSize(18);
        transcriptView.setTextColor(Color.rgb(30, 31, 40));
        transcriptView.setTextIsSelectable(true);
        transcriptView.setGravity(Gravity.TOP | Gravity.START);
        transcriptView.setPadding(dp(14), dp(14), dp(14), dp(14));
        transcriptView.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams transcriptParams = new LinearLayout.LayoutParams(-1, dp(170));
        transcriptParams.bottomMargin = dp(10);
        content.addView(transcriptView, transcriptParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        copyButton = new Button(this);
        copyButton.setText("Copy");
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(view -> copyTranscript());
        actions.addView(copyButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        shareButton = new Button(this);
        shareButton.setText("Share");
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(view -> shareTranscript());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        shareParams.leftMargin = dp(8);
        actions.addView(shareButton, shareParams);
        content.addView(actions);

        TextView note = new TextView(this);
        note.setText("Audio is sent only to the provider configured above. Android does not expose the Mac app's global shortcut or cross-app paste APIs, so use Copy or Share after transcription.");
        note.setTextSize(12);
        note.setTextColor(Color.rgb(105, 106, 118));
        note.setPadding(0, dp(18), 0, 0);
        content.addView(note, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(248, 249, 252));
        scrollView.addView(content, new ViewGroup.LayoutParams(-1, -2));
        return scrollView;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Color.rgb(45, 46, 60));
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(8), 0, dp(4));
        return view;
    }

    private EditText editText(String hint, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(dp(12), 0, dp(12), 0);
        if (secret) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return field;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.bottomMargin = dp(2);
        return params;
    }

    private void onRecordClicked() {
        if (recording) {
            finishRecording();
            return;
        }
        if (apiKeyField.getText().toString().trim().isEmpty()) {
            showStatus("Add an API key before starting dictation.");
            apiKeyField.requestFocus();
            return;
        }
        saveSettings();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        beginRecording();
    }

    private void beginRecording() {
        try {
            recorder = new WavRecorder(getCacheDir());
            recorder.start();
            recording = true;
            recordButton.setText("Stop and transcribe");
            setSettingsEnabled(false);
            showStatus("Listening… speak naturally, then tap Stop and transcribe.");
        } catch (IOException | RuntimeException exception) {
            recorder = null;
            showStatus("Could not start microphone: " + safeMessage(exception));
        }
    }

    private void finishRecording() {
        if (recorder == null) {
            return;
        }
        recording = false;
        recordButton.setEnabled(false);
        recordButton.setText("Transcribing…");
        showStatus("Preparing audio…");
        final WavRecorder finishedRecorder = recorder;
        recorder = null;

        final File audioFile;
        try {
            audioFile = finishedRecorder.stop();
        } catch (IOException exception) {
            recordButton.setEnabled(true);
            recordButton.setText("Start dictation");
            setSettingsEnabled(true);
            showStatus("Could not finish recording: " + safeMessage(exception));
            return;
        }

        ProviderClient.Settings settings = settings();
        boolean shouldClean = cleanupCheckBox.isChecked();
        requestExecutor.execute(() -> {
            try {
                String transcript = ProviderClient.transcribe(audioFile, settings);
                String result = transcript;
                if (shouldClean) {
                    mainHandler.post(() -> showStatus("Transcription received. Cleaning up…"));
                    try {
                        result = ProviderClient.clean(transcript, settings);
                    } catch (IOException cleanupException) {
                        // The raw transcription is still useful when cleanup is unavailable.
                        result = transcript;
                        String message = safeMessage(cleanupException);
                        mainHandler.post(() -> showStatus("Transcribed. Cleanup unavailable: " + message));
                    }
                }
                final String displayed = result;
                mainHandler.post(() -> showResult(displayed));
            } catch (Exception exception) {
                mainHandler.post(() -> showFailure(safeMessage(exception)));
            } finally {
                if (!audioFile.delete()) {
                    // Cache files are removed by the system when space is needed.
                }
            }
        });
    }

    private void showResult(String result) {
        transcriptView.setText(result);
        copyButton.setEnabled(true);
        shareButton.setEnabled(true);
        recordButton.setEnabled(true);
        recordButton.setText("Start dictation");
        setSettingsEnabled(true);
        showStatus("Done. Copy or share the transcript.");
    }

    private void showFailure(String message) {
        recordButton.setEnabled(true);
        recordButton.setText("Start dictation");
        setSettingsEnabled(true);
        showStatus(message);
    }

    private void copyTranscript() {
        String text = transcriptView.getText().toString().trim();
        if (text.isEmpty() || text.equals("Your transcript will appear here.")) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("FreeFlow transcript", text));
        Toast.makeText(this, "Transcript copied", Toast.LENGTH_SHORT).show();
    }

    private void shareTranscript() {
        String text = transcriptView.getText().toString().trim();
        if (text.isEmpty() || text.equals("Your transcript will appear here.")) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, "Share transcript"));
    }

    private ProviderClient.Settings settings() {
        return new ProviderClient.Settings(
                apiKeyField.getText().toString(),
                baseUrlField.getText().toString(),
                transcriptionModelField.getText().toString(),
                cleanupModelField.getText().toString()
        );
    }

    private void saveSettings() {
        SecretStore.write(this, apiKeyField.getText().toString());
        preferences.edit()
                .putString(BASE_URL, baseUrlField.getText().toString().trim())
                .putString(TRANSCRIPTION_MODEL, transcriptionModelField.getText().toString().trim())
                .putString(CLEANUP_MODEL, cleanupModelField.getText().toString().trim())
                .putBoolean(CLEANUP_ENABLED, cleanupCheckBox.isChecked())
                .apply();
    }

    private void setSettingsEnabled(boolean enabled) {
        apiKeyField.setEnabled(enabled);
        baseUrlField.setEnabled(enabled);
        transcriptionModelField.setEnabled(enabled);
        cleanupModelField.setEnabled(enabled);
        cleanupCheckBox.setEnabled(enabled);
    }

    private void showStatus(String status) {
        statusView.setText(status);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "The operation failed." : message.trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beginRecording();
            } else {
                showStatus("Microphone permission is required for dictation.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (recording && recorder != null) {
            try {
                recorder.stop();
            } catch (IOException ignored) {
                // The Activity is being destroyed; the temporary file is in the cache directory.
            }
            recording = false;
        }
        requestExecutor.shutdownNow();
        super.onDestroy();
    }
}
