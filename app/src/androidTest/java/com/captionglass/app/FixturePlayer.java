package com.captionglass.app;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.widget.Button;

/** Separate test APK/UID, with an explicit allow-capture media policy and synthetic assets only. */
public class FixturePlayer extends Activity {
    private MediaPlayer player;
    private int taps;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Button button = new Button(this);
        button.setText("Play synthetic test audio");
        setContentView(button);
        button.setOnClickListener(view -> {
            try {
                if (player != null) player.release();
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build());
                String language = getIntent().getStringExtra("language");
                String name = "ja".equals(language) ? "ja.wav" : "zh".equals(language) ? "zh.wav" : "en.wav";
                try (AssetFileDescriptor file = getAssets().openFd(name)) {
                    player.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
                }
                player.setLooping(true);
                player.prepare();
                player.start();
                button.setText(String.format(java.util.Locale.ROOT, "Playing synthetic audio (%d) · close to stop", ++taps));
            } catch (Exception error) { button.setText(error.toString()); }
        });
    }
    @Override public void onDestroy() { if (player != null) player.release(); super.onDestroy(); }
}
