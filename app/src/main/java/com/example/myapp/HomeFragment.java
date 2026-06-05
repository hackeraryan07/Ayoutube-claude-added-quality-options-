package com.example.myapp;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.myapp.extractor.YouTubeExtractorService;
import com.example.myapp.model.UserPreferences;
import com.example.myapp.model.VideoItem;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";
    private static final String ARG_TAB = "tab_index";

    /** Matches MainActivity.TAB_NAMES order */
    private static final String[] TAB_QUERIES = {
        null,                       // 0 Home  — built from prefs
        null,                       // 1 Trending — kiosk
        "best music videos 2024",   // 2 Music
        "gaming highlights 2024",   // 3 Gaming
        "world news today",         // 4 News
        "sports highlights 2024",   // 5 Sports
        "best comedy videos",       // 6 Comedy
        "educational videos"        // 7 Education
    };

    private int mTabIndex;

    private SwipeRefreshLayout mSwipeRefresh;
    private ProgressBar        mProgressBar;
    private View               mEmptyState;
    private TextView           mErrorText;
    private VideoCardAdapter   mAdapter;

    private final CompositeDisposable mDisposables = new CompositeDisposable();

    public static HomeFragment newInstance(int tabIndex) {
        Bundle args = new Bundle();
        args.putInt(ARG_TAB, tabIndex);
        HomeFragment f = new HomeFragment();
        f.setArguments(args);
        return f;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mTabIndex = getArguments().getInt(ARG_TAB, 0);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Root IS the SwipeRefreshLayout
        mSwipeRefresh = (SwipeRefreshLayout) view;
        mSwipeRefresh.setColorSchemeColors(
                ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        mSwipeRefresh.setProgressBackgroundColorSchemeColor(
                ContextCompat.getColor(requireContext(), R.color.colorSurface));

        RecyclerView rv     = view.findViewById(R.id.recycler_view);
        mProgressBar        = view.findViewById(R.id.progress_bar);
        mEmptyState         = view.findViewById(R.id.empty_state);
        mErrorText          = view.findViewById(R.id.error_text);
        MaterialButton retryBtn = view.findViewById(R.id.retry_btn);

        mAdapter = new VideoCardAdapter(this::openPlayer);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(mAdapter);
        // Smooth fling
        rv.setHasFixedSize(false);

        mSwipeRefresh.setOnRefreshListener(this::loadData);
        retryBtn.setOnClickListener(v -> {
            mEmptyState.setVisibility(View.GONE);
            loadData();
        });

        loadData();
    }

    @Override
    public void onDestroyView() {
        mDisposables.clear();
        super.onDestroyView();
    }

    // ── Data ───────────────────────────────────────────────────────────────

    private void loadData() {
        if (!isAdded()) return;
        showLoading();

        if (mTabIndex == 1) {
            // Trending via kiosk (with search fallback built into service)
            mDisposables.add(
                YouTubeExtractorService.getInstance().getTrending()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::onVideosLoaded,
                               err -> onLoadError(err.getMessage()))
            );
        } else {
            String query = resolveQuery();
            mDisposables.add(
                YouTubeExtractorService.getInstance().getByCategory(query)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::onVideosLoaded,
                               err -> onLoadError(err.getMessage()))
            );
        }
    }

    private String resolveQuery() {
        String q = TAB_QUERIES[mTabIndex];
        if (q != null) return q;
        // Home tab — personalised
        UserPreferences prefs = new UserPreferences(requireContext());
        Set<String> interests = prefs.getInterests();
        String interest = interests.isEmpty() ? "Trending" : interests.iterator().next();
        return UserPreferences.interestToQuery(interest);
    }

    // ── UI state helpers ──────────────────────────────────────────────────

    private void showLoading() {
        if (!isAdded()) return;
        if (mAdapter.getItemCount() == 0) {
            mProgressBar.setVisibility(View.VISIBLE);
        }
        mEmptyState.setVisibility(View.GONE);
    }

    private void onVideosLoaded(List<VideoItem> videos) {
        if (!isAdded()) return;
        mProgressBar.setVisibility(View.GONE);
        mSwipeRefresh.setRefreshing(false);

        if (videos == null || videos.isEmpty()) {
            mAdapter.setVideos(java.util.Collections.emptyList());
            mErrorText.setText(R.string.no_results);
            mEmptyState.setVisibility(View.VISIBLE);
        } else {
            mEmptyState.setVisibility(View.GONE);
            mAdapter.setVideos(videos);
        }
    }

    private void onLoadError(String msg) {
        if (!isAdded()) return;
        Log.e(TAG, "Load error [tab=" + mTabIndex + "]: " + msg);
        mProgressBar.setVisibility(View.GONE);
        mSwipeRefresh.setRefreshing(false);
        if (mAdapter.getItemCount() == 0) {
            mErrorText.setText(R.string.error_load);
            mEmptyState.setVisibility(View.VISIBLE);
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    private void openPlayer(VideoItem video) {
        new UserPreferences(requireContext()).recordWatched(video.getVideoId());
        Intent i = new Intent(requireActivity(), PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_VIDEO, video);
        startActivity(i);
    }
}
