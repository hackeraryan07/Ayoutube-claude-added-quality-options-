package com.example.myapp.extractor;

import android.util.Log;

import com.example.myapp.model.VideoItem;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * YouTube extraction via NewPipe Extractor v0.26.2.
 *
 * v0.24+ API changes applied:
 *  - getThumbnailUrl() removed → use getThumbnails().get(0).getUrl()
 *  - stream.content (was package-private) → use stream.getContent() (v0.26.2+)
 *
 * Quality fix:
 *  - getVideoStreams() returns progressive streams only (≤360p on YouTube)
 *  - getVideoOnlyStreams() returns DASH adaptive streams (480p, 720p, 1080p, etc.)
 *  - Both are now included; video-only streams carry a separate audioUrl for merging in PlayerActivity
 */
public class YouTubeExtractorService {

    private static final String TAG = "YTExtractor";
    private static volatile YouTubeExtractorService sInstance;
    private final StreamingService mYT;

    private YouTubeExtractorService() {
        mYT = ServiceList.YouTube;
    }

    public static YouTubeExtractorService getInstance() {
        if (sInstance == null) {
            synchronized (YouTubeExtractorService.class) {
                if (sInstance == null) sInstance = new YouTubeExtractorService();
            }
        }
        return sInstance;
    }

    // ── Search ────────────────────────────────────────────────────────────

    public Single<List<VideoItem>> search(String query) {
        return Single.fromCallable(() -> runSearch(query)).subscribeOn(Schedulers.io());
    }

    private List<VideoItem> runSearch(String q) throws Exception {
        SearchExtractor ex = mYT.getSearchExtractor(q);
        ex.fetchPage();
        return toItems(ex.getInitialPage().getItems());
    }

    // ── Trending ──────────────────────────────────────────────────────────

    public Single<List<VideoItem>> getTrending() {
        return Single.fromCallable(this::runTrending)
                     .subscribeOn(Schedulers.io())
                     .onErrorResumeNext(e -> {
                         Log.w(TAG, "Kiosk failed: " + safe(e) + " — fallback to search");
                         return search("trending videos today");
                     });
    }

    private List<VideoItem> runTrending() throws Exception {
        KioskExtractor kiosk = mYT.getKioskList().getDefaultKioskExtractor();
        kiosk.fetchPage();
        return toItems(kiosk.getInitialPage().getItems());
    }

    // ── Category ──────────────────────────────────────────────────────────

    public Single<List<VideoItem>> getByCategory(String query) {
        return search(query);
    }

    // ── Stream URL (kept for backwards compat) ────────────────────────────

    public Single<String> getStreamUrl(String videoId) {
        return Single.fromCallable(() -> resolveStream(videoId)).subscribeOn(Schedulers.io());
    }

    private String resolveStream(String videoId) throws Exception {
        String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
        StreamInfo info;
        try {
            info = StreamInfo.getInfo(mYT, watchUrl);
        } catch (Exception e) {
            throw new Exception("Extraction failed: " + safe(e), e);
        }

        // Pass 1: progressive video+audio streams, best quality <= 720p
        // NOTE: v0.26.2 made content field private — must use getContent()
        List<VideoStream> progressive = info.getVideoStreams();
        if (progressive != null && !progressive.isEmpty()) {
            String best = null;
            int bestRes = 0;
            for (VideoStream vs : progressive) {
                String u = vs.getContent();
                if (u == null || u.isEmpty()) continue;
                int r = parseRes(vs.getResolution());
                if (r > bestRes && r <= 720) { bestRes = r; best = u; }
            }
            if (best != null) return best;
            // fallback: any progressive stream
            for (VideoStream vs : progressive) {
                String u = vs.getContent();
                if (u != null && !u.isEmpty()) return u;
            }
        }

        // Pass 2: HLS manifest (audio+video, works great with ExoPlayer)
        String hls = info.getHlsUrl();
        if (hls != null && !hls.isEmpty()) return hls;

        // Pass 3: audio-only stream (better than nothing)
        List<AudioStream> audioStreams = info.getAudioStreams();
        if (audioStreams != null && !audioStreams.isEmpty()) {
            String u = audioStreams.get(0).getContent();
            if (u != null && !u.isEmpty()) return u;
        }

        // Pass 4: video-only adaptive (no audio, last resort)
        List<VideoStream> adaptive = info.getVideoOnlyStreams();
        if (adaptive != null) {
            for (VideoStream vs : adaptive) {
                String u = vs.getContent();
                if (u != null && !u.isEmpty()) return u;
            }
        }

        throw new Exception("No playable stream for " + videoId);
    }

    // ── Quality Streams ───────────────────────────────────────────────────

    /**
     * Holds one quality option.
     * - url      : the video stream URL
     * - audioUrl : non-null when url is video-only (DASH); PlayerActivity must merge them.
     *              null for progressive streams (video+audio in one URL).
     */
    public static class StreamQuality {
        public final String label;
        public final String url;
        public final String audioUrl; // null = progressive (no merge needed)

        public StreamQuality(String label, String url, String audioUrl) {
            this.label    = label;
            this.url      = url;
            this.audioUrl = audioUrl;
        }
    }

    public Single<List<StreamQuality>> getStreamQualities(String videoId) {
        return Single.fromCallable(() -> resolveQualities(videoId)).subscribeOn(Schedulers.io());
    }

    private List<StreamQuality> resolveQualities(String videoId) throws Exception {
        String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
        StreamInfo info;
        try {
            info = StreamInfo.getInfo(mYT, watchUrl);
        } catch (Exception e) {
            throw new Exception("Extraction failed: " + safe(e), e);
        }

        List<StreamQuality> result = new ArrayList<>();

        // ── Step 1: Find the best audio-only stream for merging with video-only streams ──
        // YouTube's high-quality streams (480p+) are video-only DASH; they need a
        // separate audio track merged in by ExoPlayer (MergingMediaSource).
        String bestAudioUrl = null;
        List<AudioStream> audioStreams = info.getAudioStreams();
        if (audioStreams != null) {
            int bestBitrate = 0;
            for (AudioStream as : audioStreams) {
                String u = as.getContent();
                if (u == null || u.isEmpty()) continue;
                // Prefer PROGRESSIVE_HTTP audio streams; they work best with ExoPlayer merging
                if (as.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
                int br = as.getBitrate();
                if (br > bestBitrate) {
                    bestBitrate = br;
                    bestAudioUrl = u;
                }
            }
            // Fallback: accept any audio stream if no PROGRESSIVE_HTTP found
            if (bestAudioUrl == null) {
                for (AudioStream as : audioStreams) {
                    String u = as.getContent();
                    if (u != null && !u.isEmpty()) {
                        bestAudioUrl = u;
                        break;
                    }
                }
            }
        }

        // ── Step 2: Progressive streams (video+audio combined) — usually ≤360p ──
        List<VideoStream> progressive = info.getVideoStreams();
        if (progressive != null) {
            for (VideoStream vs : progressive) {
                if (vs.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
                String u = vs.getContent();
                if (u == null || u.isEmpty()) continue;
                String res = vs.getResolution();
                if (res == null || res.isEmpty()) res = "Unknown";
                // audioUrl = null because this stream already has audio baked in
                result.add(new StreamQuality(res, u, null));
            }
        }

        // ── Step 3: Video-only adaptive streams (DASH) — 480p, 720p, 1080p, 1440p, 2160p ──
        // These are the streams YouTube actually serves for high qualities.
        List<VideoStream> adaptive = info.getVideoOnlyStreams();
        if (adaptive != null) {
            for (VideoStream vs : adaptive) {
                if (vs.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
                String u = vs.getContent();
                if (u == null || u.isEmpty()) continue;
                String res = vs.getResolution();
                if (res == null || res.isEmpty()) res = "Unknown";
                // Mark fps if available (e.g. "1080p60")
                int fps = vs.getFps();
                String label = (fps > 30) ? res + "p" + fps : res;
                // audioUrl is required — ExoPlayer will merge video + audio
                result.add(new StreamQuality(label, u, bestAudioUrl));
            }
        }

        // ── Step 4: Deduplicate by resolution label, keeping first occurrence ──
        List<StreamQuality> deduped = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (StreamQuality sq : result) {
            if (!seen.contains(sq.label)) {
                seen.add(sq.label);
                deduped.add(sq);
            }
        }
        result = deduped;

        // ── Step 5: Sort by resolution descending (2160 > 1080 > 720 > 480 > 360 …) ──
        Collections.sort(result, new Comparator<StreamQuality>() {
            @Override
            public int compare(StreamQuality a, StreamQuality b) {
                return parseRes(b.label) - parseRes(a.label);
            }
        });

        // ── Step 6: HLS as final fallback (adaptive, auto quality) ──
        String hls = info.getHlsUrl();
        if (hls != null && !hls.isEmpty()) {
            result.add(new StreamQuality("Auto (HLS)", hls, null));
        }

        if (result.isEmpty()) {
            throw new Exception("No playable streams found for " + videoId);
        }
        return result;
    }

    // ── Item parsing ──────────────────────────────────────────────────────

    private List<VideoItem> toItems(List<InfoItem> items) {
        List<VideoItem> out = new ArrayList<>();
        if (items == null) return out;

        for (InfoItem item : items) {
            if (!(item instanceof StreamInfoItem)) continue;
            StreamInfoItem si = (StreamInfoItem) item;

            String videoId = videoId(si.getUrl());
            if (videoId == null || videoId.isEmpty()) continue;

            // v0.24+ API: getThumbnails() returns List<Image>
            String thumb = "";
            try {
                List<Image> thumbs = si.getThumbnails();
                if (thumbs != null && !thumbs.isEmpty()) {
                    thumb = thumbs.get(0).getUrl();
                    if (thumb == null) thumb = "";
                }
            } catch (Exception ignored) {}

            out.add(new VideoItem(
                videoId,
                si.getName()              != null ? si.getName()              : "Unknown",
                si.getUploaderName()      != null ? si.getUploaderName()      : "",
                thumb,
                fmtDuration(si.getDuration()),
                si.getViewCount(),
                si.getTextualUploadDate() != null ? si.getTextualUploadDate() : ""
            ));
        }
        return out;
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static String videoId(String url) {
        if (url == null) return null;
        int i = url.indexOf("v=");
        if (i >= 0) {
            String id = url.substring(i + 2);
            int a = id.indexOf('&');
            return a < 0 ? id : id.substring(0, a);
        }
        if (url.contains("youtu.be/")) {
            String[] p = url.split("youtu.be/");
            if (p.length > 1) {
                String id = p[1];
                int q = id.indexOf('?');
                return q < 0 ? id : id.substring(0, q);
            }
        }
        return null;
    }

    private static int parseRes(String r) {
        if (r == null) return 0;
        try { return Integer.parseInt(r.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String fmtDuration(long sec) {
        if (sec <= 0) return "";
        long h = sec/3600, m = (sec%3600)/60, s = sec%60;
        return h > 0 ? String.format("%d:%02d:%02d",h,m,s) : String.format("%d:%02d",m,s);
    }

    private static String safe(Throwable e) {
        return e != null && e.getMessage() != null ? e.getMessage()
             : e != null ? e.getClass().getSimpleName() : "null";
    }
}
