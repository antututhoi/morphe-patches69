package app.morphe.extension.youtube.patches.utils.requests;

import org.json.JSONObject;

import java.net.HttpURLConnection;

import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

/** Fetches title, author, channel id and publish date of a video. Blocking: call off the main thread. */
public final class VideoMetaRequest {

    private static final Route.CompiledRoute ROUTE = new Route(
            Route.Method.POST,
            "player?prettyPrint=false"
                    + "&fields=videoDetails(title,author,channelId),microformat.playerMicroformatRenderer(publishDate,uploadDate)"
    ).compile();

    public static final class Meta {
        public String title = "", author = "", channelId = "", date = ""; // date: yyyy-MM-dd or empty
    }

    private VideoMetaRequest() {}

    public static Meta fetch(String videoId) throws Exception {
        byte[] body = ChannelIdRoutes.createBody(videoId);
        HttpURLConnection c = ChannelIdRoutes.getConnection(ROUTE);
        c.setFixedLengthStreamingMode(body.length);
        c.getOutputStream().write(body);
        int code = c.getResponseCode();
        if (code != 200) throw new java.io.IOException("Video info request failed: HTTP " + code);
        JSONObject json = Requester.parseJSONObject(c);
        Meta m = new Meta();
        JSONObject d = json.optJSONObject("videoDetails");
        if (d != null) {
            m.title = d.optString("title", "");
            m.author = d.optString("author", "");
            m.channelId = d.optString("channelId", "");
        }
        JSONObject mf = json.optJSONObject("microformat");
        JSONObject r = mf == null ? null : mf.optJSONObject("playerMicroformatRenderer");
        if (r != null) {
            String date = r.optString("publishDate", r.optString("uploadDate", ""));
            m.date = date.length() >= 10 ? date.substring(0, 10) : "";
        }
        return m;
    }
}
