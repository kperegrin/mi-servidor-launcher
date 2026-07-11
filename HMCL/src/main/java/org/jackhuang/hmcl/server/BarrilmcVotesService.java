/*
 * BarrilMC Launcher
 * Copyright (C) 2026 BarrilMC contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jackhuang.hmcl.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Backend access for the weekly votes, stored in Firebase as:
/// <pre>
/// /polls/&lt;pollId&gt;  = { question, options: { optId: "label", ... }, open: true, ts }
/// /ballots/&lt;pollId&gt;/&lt;userName&gt; = "optId"   (one ballot per user, overwritable)
/// </pre>
/// Tallies are computed client-side from the ballots so changing a vote just overwrites the user's
/// single entry.
@NotNullByDefault
public final class BarrilmcVotesService {

    private BarrilmcVotesService() {
    }

    /// A poll definition plus its live tally.
    public static final class Poll {
        private final String id;
        private final String question;
        private final boolean open;
        private final long timestamp;
        /// Option id -> label, in display order.
        private final LinkedHashMap<String, String> options;
        /// Option id -> vote count.
        private final Map<String, Integer> counts;
        /// The option id the current user already voted for, or {@code null}.
        private final @Nullable String myVote;

        Poll(String id, String question, boolean open, long timestamp,
             LinkedHashMap<String, String> options, Map<String, Integer> counts, @Nullable String myVote) {
            this.id = id;
            this.question = question;
            this.open = open;
            this.timestamp = timestamp;
            this.options = options;
            this.counts = counts;
            this.myVote = myVote;
        }

        public String getId() {
            return id;
        }

        public String getQuestion() {
            return question;
        }

        public boolean isOpen() {
            return open;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public LinkedHashMap<String, String> getOptions() {
            return options;
        }

        public int getCount(String optionId) {
            return counts.getOrDefault(optionId, 0);
        }

        public int getTotalVotes() {
            int total = 0;
            for (int c : counts.values()) {
                total += c;
            }
            return total;
        }

        public @Nullable String getMyVote() {
            return myVote;
        }
    }

    /// Loads all polls (newest first) with their tallies and the current user's existing votes.
    public static List<Poll> fetchPolls(String userName) {
        JsonElement pollsEl = BarrilmcCloud.get("polls", null);
        List<Poll> polls = new ArrayList<>();
        if (pollsEl == null || !pollsEl.isJsonObject()) {
            return polls;
        }
        for (Map.Entry<String, JsonElement> entry : pollsEl.getAsJsonObject().entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject obj = entry.getValue().getAsJsonObject();
            String id = entry.getKey();
            String question = obj.has("question") && obj.get("question").isJsonPrimitive()
                    ? obj.get("question").getAsString() : "(sin pregunta)";
            boolean open = !obj.has("open") || !obj.get("open").isJsonPrimitive() || obj.get("open").getAsBoolean();
            long ts = obj.has("ts") && obj.get("ts").isJsonPrimitive() ? obj.get("ts").getAsLong() : 0L;

            LinkedHashMap<String, String> options = new LinkedHashMap<>();
            if (obj.has("options") && obj.get("options").isJsonObject()) {
                for (Map.Entry<String, JsonElement> opt : obj.getAsJsonObject("options").entrySet()) {
                    if (opt.getValue() != null && opt.getValue().isJsonPrimitive()) {
                        options.put(opt.getKey(), opt.getValue().getAsString());
                    }
                }
            }

            Map<String, Integer> counts = new LinkedHashMap<>();
            String myVote = null;
            JsonElement ballots = BarrilmcCloud.get("ballots/" + id, null);
            if (ballots != null && ballots.isJsonObject()) {
                for (Map.Entry<String, JsonElement> ballot : ballots.getAsJsonObject().entrySet()) {
                    if (ballot.getValue() == null || !ballot.getValue().isJsonPrimitive()) {
                        continue;
                    }
                    String choice = ballot.getValue().getAsString();
                    counts.merge(choice, 1, Integer::sum);
                    if (ballot.getKey().equals(userName)) {
                        myVote = choice;
                    }
                }
            }
            polls.add(new Poll(id, question, open, ts, options, counts, myVote));
        }
        // Newest first.
        polls.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return polls;
    }

    /// Records (or changes) the user's vote on a poll. Returns true on success.
    public static boolean vote(String pollId, String userName, String optionId) {
        // Store the option id as a JSON string value at /ballots/<pollId>/<userName>.
        return BarrilmcCloud.put("ballots/" + pollId + "/" + userName, "\"" + optionId + "\"");
    }
}
