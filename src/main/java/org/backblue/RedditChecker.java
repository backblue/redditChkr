package org.backblue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RedditChecker {

    private final static ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final static HashMap<String, String> subredditMap = new HashMap<>();
    private final static HashMap<String, String> subredditLastPost = new HashMap<>();

    public static void main(String[] args) {
        try {
            loadData();
        } catch (IOException e) {
            System.err.println(e);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(scheduler::shutdownNow));

    }

    private static void loadData() throws IOException {
        Path path = Path.of("list.json");
        JSONObject json = new JSONObject(Files.readString(path));
        for (String key : json.keySet()) {
            String lastPostID = fetchData(key).getJSONObject(0).getJSONObject("data").getString("id");
            subredditMap.put(key, json.getString(key));
            subredditLastPost.put(key, lastPostID);
            scheduler.scheduleWithFixedDelay(() -> {try {checkSubreddit(key);} catch (Exception ignored) {}}, 0, 1, TimeUnit.MINUTES);
        }
    }

    private static void checkSubreddit(String subreddit) throws Exception {
        JSONArray jsonArray = fetchData(subreddit);
        int i = 0;
        String firstNewPostID = null;
        for (; i < jsonArray.length(); i++) {
            JSONObject a = jsonArray.getJSONObject(i).getJSONObject("data");
            if (i == 0) {
                firstNewPostID = a.getString("id");
            }
            if (a.getString("id").equals(subredditLastPost.get(subreddit))) {
                break;
            }
        }
        subredditLastPost.put(subreddit, firstNewPostID);
        for (; i > 0; i--) {

            String postPrefix = "";

            JSONObject a = jsonArray.getJSONObject(i - 1).getJSONObject("data");

            JSONObject payload = new JSONObject();
            JSONObject embed = new JSONObject();
            embed.put("title", a.get("title"));
            embed.put("description", a.get("selftext"));
            embed.put("url", "https://reddit.com" + a.get("permalink"));
            embed.put("color", JSONObject.NULL);
            embed.put("footer", new JSONObject().put("text", "u/" + a.get("author")));
            embed.put("timestamp", Instant.ofEpochSecond(a.getLong("created_utc")).toString());
            if (a.has("preview")) {
                embed.put("image", new JSONObject().put("url", a.getString("url_overridden_by_dest")));
                postPrefix = " image";
            } else if (a.has("crosspost_parent_list")) {
                postPrefix = " link";
            }

            embed.put("author", new JSONObject()
                    .put("name", "New" + postPrefix + " post in r/" + subreddit)
                    .put("url", "https://reddit.com" + a.get("permalink"))
                    .put("icon_url", getIcon(subreddit)));
            payload.put("content", JSONObject.NULL);
            payload.put("attachments", new JSONArray());
            payload.put("embeds", new JSONArray().put(embed));
            webHook(subredditMap.get(subreddit), payload);
        }
    }

    private static JSONArray fetchData(String subreddit) throws IOException {
        String url = "https://www.reddit.com/r/" + subreddit + "/new.json";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JSONObject jsonResponse = new JSONObject(response.body());
                return jsonResponse.getJSONObject("data").getJSONArray("children");
            } else {
                throw new IOException("Failed to fetch data, status code: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error fetching data for subreddit: " + subreddit);
            e.printStackTrace();
        }
        throw new IOException("A exception should've been thrown.");
    }

    private static void webHook(String link, JSONObject postData) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(link))
                .header("User-Agent", "Mozilla/5.0")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(postData.toString()))
                .build();
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 204 && response.statusCode() != 200) {
                System.err.println("Failed to send webhook, status code: " + response.statusCode() + "\n Response: " + response.body() + "\n Payload: " + postData);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error sending webhook");
            e.printStackTrace();
        }
    }

    private static String getIcon(String subreddit) {
        try {
            String url = "https://www.reddit.com/r/" + subreddit + "/about.json";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            var client = java.net.http.HttpClient.newHttpClient();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JSONObject jsonResponse = new JSONObject(response.body());
                String link = jsonResponse.getJSONObject("data").getString("community_icon");
                int cutoff = link.indexOf(".png");
                return link.substring(0, cutoff + 4);
            }
        } catch (IOException | InterruptedException e) {
            return "";
        }
        return "";
    }

}