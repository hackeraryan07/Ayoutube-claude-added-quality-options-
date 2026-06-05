package com.example.myapp;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapp.extractor.YouTubeExtractorService;
import com.example.myapp.model.VideoItem;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * Full mobile search screen — no Leanback dependency.
 * Debounced search via RxJava PublishSubject (600ms).
 */
public class SearchActivity extends AppCompatActivity {

    private static final long   DEBOUNCE_MS = 600;

    private VideoCardAdapter     mAdapter;
    private ProgressBar          mProgress;
    private TextView             mNoResults;

    private final CompositeDisposable mDisposables = new CompositeDisposable();
    private final PublishSubject<String> mQueryBus = PublishSubject.create();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        // Back button
        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        // RecyclerView
        RecyclerView rv = findViewById(R.id.search_recycler);
        mProgress       = findViewById(R.id.search_progress);
        mNoResults      = findViewById(R.id.no_results);

        mAdapter = new VideoCardAdapter(this::openPlayer);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(mAdapter);

        // Search field
        TextInputEditText editText = findViewById(R.id.search_edit_text);

        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int cnt, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = (s != null) ? s.toString().trim() : "";
                if (q.isEmpty()) {
                    mAdapter.setVideos(new ArrayList<>());
                    mNoResults.setVisibility(View.GONE);
                    mProgress.setVisibility(View.GONE);
                } else {
                    mQueryBus.onNext(q);
                }
            }
        });

        editText.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                             && event.getAction() == KeyEvent.ACTION_DOWN);
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                CharSequence txt = v.getText();
                String q = (txt != null) ? txt.toString().trim() : "";
                if (!q.isEmpty()) mQueryBus.onNext(q);
                return true;
            }
            return false;
        });

        // RxJava debounced search pipeline
        mDisposables.add(
            mQueryBus
                .debounce(DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(q -> {
                    mProgress.setVisibility(View.VISIBLE);
                    mNoResults.setVisibility(View.GONE);
                    mAdapter.setVideos(new ArrayList<>());
                })
                .observeOn(Schedulers.io())
                .flatMapSingle(q ->
                    YouTubeExtractorService.getInstance()
                        .search(q)
                        .onErrorReturn(e -> new ArrayList<>())
                )
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    this::showResults,
                    e -> {
                        mProgress.setVisibility(View.GONE);
                        mNoResults.setText(R.string.error_load);
                        mNoResults.setVisibility(View.VISIBLE);
                    }
                )
        );
    }

    @Override
    protected void onDestroy() {
        mDisposables.clear();
        super.onDestroy();
    }

    private void showResults(List<VideoItem> videos) {
        mProgress.setVisibility(View.GONE);
        if (videos == null || videos.isEmpty()) {
            mAdapter.setVideos(new ArrayList<>());
            mNoResults.setText(R.string.no_results);
            mNoResults.setVisibility(View.VISIBLE);
        } else {
            mNoResults.setVisibility(View.GONE);
            mAdapter.setVideos(videos);
        }
    }

    private void openPlayer(VideoItem video) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_VIDEO, video);
        startActivity(i);
    }
}
