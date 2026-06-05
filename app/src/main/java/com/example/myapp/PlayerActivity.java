package com.example.myapp;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.example.myapp.extractor.YouTubeExtractorService;
import com.example.myapp.model.VideoItem;
import com.google.android.material.button.MaterialButton;

import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.functions.Consumer;

/**
 * Full-screen ExoPlayer activity.
 *
 * startPlayback() handles two cases:
 *   audioUrl == null → plain progressive stream, play directly with MediaItem
 *   audioUrl != null → video-only DASH stream, merge with audio via MergingMediaSource
 */
@SuppressWarnings("deprecation")   // setSystemUiVisibility on API 28
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO = "extra_video";
    private static final String TAG = "PlayerActivity";

    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36";

    private PlayerView     mPlayerView;
    private View           mLoadingOverlay;
    private View           mErrorOverlay;
    private TextView       mErrorText;
    private MaterialButton mRetryBtn;
    private MaterialButton mQualityBtn;

    private ExoPlayer mPlayer;
    private VideoItem mVideo;

    private DefaultHttpDataSource.Factory mDataSourceFactory;

    private List<YouTubeExtractorService.StreamQuality> mQualities;
    private int mSelectedQualityIndex = 0;

    private final CompositeDisposable mDisposables = new CompositeDisposable();

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        setContentView(R.layout.activity_player);

        mPlayerView     = findViewById(R.id.player_view);
        mLoadingOverlay = findViewById(R.id.loading_overlay);
        mErrorOverlay   = findViewById(R.id.error_overlay);
        mErrorText      = findViewById(R.id.error_text);
        mRetryBtn       = findViewById(R.id.retry_button);
        mQualityBtn     = findViewById(R.id.quality_button);

        Object extra = getIntent().getSerializableExtra(EXTRA_VIDEO);
        if (!(extra instanceof VideoItem)) { finish(); return; }
        mVideo = (VideoItem) extra;

        mRetryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mErrorOverlay.setVisibility(View.GONE);
                loadAndPlay();
            }
        });

        mQualityBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQualityDialog();
            }
        });

        buildPlayer();
        loadAndPlay();
    }

    @Override protected void onStart()  { super.onStart();  if (mPlayer != null) mPlayer.play(); }
    @Override protected void onStop()   { super.onStop();   if (mPlayer != null) mPlayer.pause(); }

    @Override
    protected void onDestroy() {
        mDisposables.clear();
        releasePlayer();
        super.onDestroy();
    }

    // ── Player setup ──────────────────────────────────────────────────────

    private void buildPlayer() {
        mDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true);

        mPlayer = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(mDataSourceFactory))
            .build();

        mPlayerView.setPlayer(mPlayer);
        mPlayerView.setUseController(true);
        mPlayerView.setControllerAutoShow(true);
        mPlayerView.setControllerShowTimeoutMs(3000);

        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                mLoadingOverlay.setVisibility(
                    state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY)
                    mErrorOverlay.setVisibility(View.GONE);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                Log.e(TAG, "Playback error", e);
                mLoadingOverlay.setVisibility(View.GONE);
                mErrorText.setText(getString(R.string.error_load) + "\n" +
                    (e.getMessage() != null ? e.getMessage() : "Unknown error"));
                mErrorOverlay.setVisibility(View.VISIBLE);
            }
        });
    }

    // ── Playback ──────────────────────────────────────────────────────────

    private void loadAndPlay() {
        mLoadingOverlay.setVisibility(View.VISIBLE);
        mErrorOverlay.setVisibility(View.GONE);
        mQualityBtn.setVisibility(View.GONE);

        mDisposables.add(
            YouTubeExtractorService.getInstance()
                .getStreamQualities(mVideo.getVideoId())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    new Consumer<List<YouTubeExtractorService.StreamQuality>>() {
                        @Override
                        public void accept(List<YouTubeExtractorService.StreamQuality> qualities) {
                            mQualities = qualities;
                            mSelectedQualityIndex = 0;
                            updateQualityButton();
                            startPlayback(qualities.get(0));
                        }
                    },
                    new Consumer<Throwable>() {
                        @Override
                        public void accept(Throwable err) {
                            Log.e(TAG, "Extraction error", err);
                            mLoadingOverlay.setVisibility(View.GONE);
                            mErrorText.setText(getString(R.string.error_load) + "\n" +
                                (err.getMessage() != null ? err.getMessage() : "Extraction failed"));
                            mErrorOverlay.setVisibility(View.VISIBLE);
                        }
                    }
                )
        );
    }

    /**
     * audioUrl == null → progressive (video+audio in one URL), set as MediaItem directly.
     * audioUrl != null → video-only DASH; merge video source + audio source via MergingMediaSource.
     */
    private void startPlayback(YouTubeExtractorService.StreamQuality quality) {
        if (mPlayer == null) return;
        Log.d(TAG, "Playing [" + quality.label + "] audioUrl=" +
              (quality.audioUrl != null ? "set" : "null"));

        if (quality.audioUrl == null) {
            // Progressive: one URL has both video and audio
            mPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(quality.url)));
        } else {
            // DASH video-only: must merge a video source and an audio source
            MediaSource videoSource = new ProgressiveMediaSource.Factory(mDataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(quality.url)));

            MediaSource audioSource = new ProgressiveMediaSource.Factory(mDataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(quality.audioUrl)));

            MergingMediaSource merged = new MergingMediaSource(videoSource, audioSource);
            mPlayer.setMediaSource(merged);
        }

        mPlayer.prepare();
        mPlayer.setPlayWhenReady(true);
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            mPlayer.clearVideoSurface();
            mPlayer.release();
            mPlayer = null;
        }
    }

    // ── Quality picker ────────────────────────────────────────────────────

    private void updateQualityButton() {
        if (mQualities == null || mQualities.isEmpty()) {
            mQualityBtn.setVisibility(View.GONE);
            return;
        }
        String label = mQualities.get(mSelectedQualityIndex).label;
        mQualityBtn.setText(getString(R.string.quality_label, label));
        mQualityBtn.setVisibility(View.VISIBLE);
    }

    private void showQualityDialog() {
        if (mQualities == null || mQualities.isEmpty()) return;

        final String[] labels = new String[mQualities.size()];
        for (int i = 0; i < mQualities.size(); i++) {
            labels[i] = mQualities.get(i).label;
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.quality_picker_title)
            .setSingleChoiceItems(labels, mSelectedQualityIndex,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == mSelectedQualityIndex) {
                            dialog.dismiss();
                            return;
                        }
                        mSelectedQualityIndex = which;
                        updateQualityButton();
                        dialog.dismiss();

                        long position = mPlayer != null ? mPlayer.getCurrentPosition() : 0;
                        startPlayback(mQualities.get(which));
                        if (mPlayer != null && position > 0) {
                            mPlayer.seekTo(position);
                        }
                    }
                })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
