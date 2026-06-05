package com.example.myapp;

import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.example.myapp.model.VideoItem;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for VideoItem — replaces the TV Leanback VideoCardPresenter.
 * YouTube-style vertical feed: thumbnail (16:9), duration badge, channel + meta info.
 */
public class VideoCardAdapter extends RecyclerView.Adapter<VideoCardAdapter.VideoViewHolder> {

    public interface OnVideoClickListener {
        void onVideoClick(VideoItem video);
    }

    // Placeholder palette for thumbnails that haven't loaded yet
    private static final int[] THUMB_COLORS = {
        0xFF1A1A2E, 0xFF16213E, 0xFF0F3460,
        0xFF533483, 0xFF2B2D42, 0xFF1B262C
    };

    // Avatar accent colors per channel
    private static final int[] AVATAR_COLORS = {
        0xFFE53935, 0xFFFF6D00, 0xFF00897B,
        0xFF1E88E5, 0xFF8E24AA, 0xFF43A047,
        0xFFF4511E, 0xFF039BE5, 0xFF00ACC1
    };

    private static final RequestOptions THUMB_OPTIONS = new RequestOptions()
        .centerCrop()
        .diskCacheStrategy(DiskCacheStrategy.ALL);

    private final List<VideoItem>      mVideos   = new ArrayList<>();
    private final OnVideoClickListener mListener;

    public VideoCardAdapter(OnVideoClickListener listener) {
        mListener = listener;
        setHasStableIds(false);
    }

    // ── Data ────────────────────────────────────────────────────────────────

    public void setVideos(List<VideoItem> videos) {
        mVideos.clear();
        if (videos != null) mVideos.addAll(videos);
        notifyDataSetChanged();
    }

    // ── RecyclerView.Adapter ─────────────────────────────────────────────────

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_video_card, parent, false);
        return new VideoViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder h, int pos) {
        VideoItem video = mVideos.get(pos);

        // ── Title ──────────────────────────────────────────────────────────
        h.title.setText(video.getTitle());

        // ── Channel + views + date ─────────────────────────────────────────
        StringBuilder meta = new StringBuilder(video.getChannelName());
        long views = video.getViewCount();
        if (views > 0) meta.append("  ·  ").append(formatViews(views)).append(" views");
        if (video.getUploadDate() != null && !video.getUploadDate().isEmpty()) {
            meta.append("  ·  ").append(video.getUploadDate());
        }
        h.channelInfo.setText(meta.toString());

        // ── Duration badge ─────────────────────────────────────────────────
        String dur = video.getDuration();
        if (dur != null && !dur.isEmpty()) {
            h.duration.setText(dur);
            h.duration.setVisibility(View.VISIBLE);
        } else {
            h.duration.setVisibility(View.GONE);
        }

        // ── Thumbnail ──────────────────────────────────────────────────────
        int placeholder = THUMB_COLORS[pos % THUMB_COLORS.length];
        String url = video.getThumbnailUrl();
        if (url != null && !url.isEmpty()) {
            Glide.with(h.thumbnail.getContext().getApplicationContext())
                 .load(url)
                 .apply(THUMB_OPTIONS)
                 .placeholder(new ColorDrawable(placeholder))
                 .error(new ColorDrawable(placeholder))
                 .into(h.thumbnail);
        } else {
            h.thumbnail.setImageDrawable(new ColorDrawable(placeholder));
        }

        // ── Avatar (colored circle) ────────────────────────────────────────
        int avatarColor = AVATAR_COLORS[pos % AVATAR_COLORS.length];
        h.channelAvatar.setBackgroundColor(avatarColor);

        // ── Click ──────────────────────────────────────────────────────────
        h.itemView.setOnClickListener(v -> {
            if (mListener != null) mListener.onVideoClick(video);
        });

        // More-button (reserved for future bottom-sheet)
        h.moreBtn.setOnClickListener(v -> { /* TODO: options sheet */ });
    }

    @Override
    public int getItemCount() {
        return mVideos.size();
    }

    @Override
    public void onViewRecycled(@NonNull VideoViewHolder h) {
        // Cancel in-flight Glide load to avoid recycled-view crash
        Glide.with(h.thumbnail.getContext().getApplicationContext())
             .clear(h.thumbnail);
        super.onViewRecycled(h);
    }

    // ── ViewHolder ────────────────────────────────────────────────────────

    static class VideoViewHolder extends RecyclerView.ViewHolder {
        final ImageView   thumbnail;
        final ImageView   channelAvatar;
        final TextView    title;
        final TextView    channelInfo;
        final TextView    duration;
        final ImageButton moreBtn;

        VideoViewHolder(@NonNull View v) {
            super(v);
            thumbnail     = v.findViewById(R.id.thumbnail);
            channelAvatar = v.findViewById(R.id.channel_avatar);
            title         = v.findViewById(R.id.title);
            channelInfo   = v.findViewById(R.id.channel_info);
            duration      = v.findViewById(R.id.duration);
            moreBtn       = v.findViewById(R.id.more_btn);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String formatViews(long v) {
        if (v >= 1_000_000_000L) return (v / 1_000_000_000L) + "B";
        if (v >= 1_000_000L)     return (v / 1_000_000L) + "M";
        if (v >= 1_000L)         return (v / 1_000L) + "K";
        return String.valueOf(v);
    }
}
