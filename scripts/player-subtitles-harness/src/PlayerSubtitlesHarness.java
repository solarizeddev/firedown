package com.solarized.firedown.harness;

import com.solarized.firedown.phone.player.PlaybackSpeeds;
import com.solarized.firedown.phone.player.PlayerSubtitles;
import com.solarized.firedown.phone.player.PlayerSubtitles.Candidate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Drives the REAL PlayerSubtitles (copied from app/src by run.sh): the
 * format of a subtitle file, the language its name declares, and which of a
 * video's downloaded subtitles shows by itself. Then the REAL PlaybackSpeeds:
 * the Playback speed sheet's choices and how each is written.
 */
public final class PlayerSubtitlesHarness {

    private static int failures;
    private static int checks;

    private static void check(String name, Object expected, Object actual) {
        checks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            System.out.println("ok   " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }

    private static Candidate c(String name, boolean linked) {
        return new Candidate(name, PlayerSubtitles.languageTag(name), linked);
    }

    private static String sniff(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return PlayerSubtitles.sniffMime(bytes, bytes.length);
    }

    public static void main(String[] args) {
        // Format: the stored mime first, then the extension.
        check("youtube text/srt is SubRip", PlayerSubtitles.MIME_SUBRIP,
                PlayerSubtitles.mimeFor("Title [en].srt", "text/srt"));
        check("vtt by mime", PlayerSubtitles.MIME_VTT,
                PlayerSubtitles.mimeFor("track", "text/vtt; charset=utf-8"));
        check("srt by extension", PlayerSubtitles.MIME_SUBRIP,
                PlayerSubtitles.mimeFor("Movie.SRT", "application/octet-stream"));
        check("ass by extension", PlayerSubtitles.MIME_SSA,
                PlayerSubtitles.mimeFor("anime.ass", null));
        check("mp4 is no subtitle", null, PlayerSubtitles.mimeFor("Title.mp4", "video/mp4"));
        check("txt is no subtitle by name", null, PlayerSubtitles.mimeFor("notes.txt", "text/plain"));

        // Sniffing a file whose name says nothing.
        check("sniff WEBVTT", PlayerSubtitles.MIME_VTT, sniff("﻿WEBVTT\n\n00:01.000 --> 00:02.000\nhi"));
        check("sniff SubRip", PlayerSubtitles.MIME_SUBRIP,
                sniff("1\n00:00:01,000 --> 00:00:02,000\nHello, world\n"));
        check("sniff headerless dot timing is refused", null,
                sniff("1\n00:00:01.000 --> 00:00:02.000\nHello, world\n"));
        check("sniff SSA", PlayerSubtitles.MIME_SSA, sniff("[Script Info]\nTitle: x"));
        check("sniff TTML", PlayerSubtitles.MIME_TTML, sniff("<?xml version=\"1.0\"?>\n<tt xmlns=\"x\">"));
        check("sniff plain text", null, sniff("just some notes, nothing timed"));

        // Language tags and stems.
        check("tag en", "en", PlayerSubtitles.languageTag("Title [en].srt"));
        check("tag en-auto", "en-auto", PlayerSubtitles.languageTag("Title [en-auto].srt"));
        check("tag pt-BR", "pt-BR", PlayerSubtitles.languageTag("Título [pt-BR].vtt"));
        check("tag before a counter", "es", PlayerSubtitles.languageTag("Title [es] (2).srt"));
        check("no tag", null, PlayerSubtitles.languageTag("Title.srt"));
        check("bracketed words are no tag", null, PlayerSubtitles.languageTag("Live [Official Video].srt"));
        check("languageOf strips auto", "en", PlayerSubtitles.languageOf("en-auto"));
        check("stem of subtitle", "title", PlayerSubtitles.stem("Title [en].srt"));
        check("stem of renamed video", "title", PlayerSubtitles.stem("Title (1).mp4"));
        check("stem of player copy", "title", PlayerSubtitles.stem("Title (2) [es].srt"));
        check("stem of renamed subtitle", "title", PlayerSubtitles.stem("Title [es] (2).srt"));
        check("display en-auto", "English (auto)",
                PlayerSubtitles.displayLanguage("en-auto", Locale.ENGLISH));
        check("display capitalised", "Inglés",
                PlayerSubtitles.displayLanguage("en", Locale.forLanguageTag("es")));
        check("display und", null, PlayerSubtitles.displayLanguage("und", Locale.ENGLISH));

        // The YouTube report: a video and its caption tracks from one page.
        List<Candidate> youtube = Arrays.asList(
                c("Title [en-auto].srt", false),
                c("Title [en].srt", false),
                c("Title [es].srt", false));
        check("phone in Spanish shows es", 2,
                PlayerSubtitles.pickDefault(youtube, "Title.mp4", 0, "es"));
        check("phone in English prefers written en over auto", 1,
                PlayerSubtitles.pickDefault(youtube, "Title.mp4", 0, "en"));
        check("phone in French still shows a written track", 1,
                PlayerSubtitles.pickDefault(youtube, "Title.mp4", 0, "fr"));
        check("only an auto track still shows", 0, PlayerSubtitles.pickDefault(
                Arrays.asList(c("Title [en-auto].srt", false)), "Title.mp4", 0, "de"));
        check("device region ignored", 1,
                PlayerSubtitles.pickDefault(youtube, "Title.mp4", 0, "en-GB"));

        // A page with several videos: only a subtitle that surely belongs shows.
        List<Candidate> newsPage = Arrays.asList(c("captions.vtt", false));
        check("other video on the page: unrelated name stays off", -1,
                PlayerSubtitles.pickDefault(newsPage, "Clip one.mp4", 1, "en"));
        check("no other video on the page: it shows", 0,
                PlayerSubtitles.pickDefault(newsPage, "Clip one.mp4", 0, "en"));
        check("other video, matching name: it shows", 0, PlayerSubtitles.pickDefault(
                Arrays.asList(c("Clip one [en].srt", false)), "Clip one.mp4", 1, "en"));
        check("other video, linked by the user: it shows", 0, PlayerSubtitles.pickDefault(
                Arrays.asList(c("random.srt", true)), "Clip one.mp4", 3, "en"));
        check("two copies of the video still match", 0, PlayerSubtitles.pickDefault(
                Arrays.asList(c("Title [en].srt", false)), "Title (1).mp4", 1, "en"));
        check("nothing", -1, PlayerSubtitles.pickDefault(
                Arrays.<Candidate>asList(), "Title.mp4", 0, "en"));

        // Names the player gives an added file.
        check("extension srt", "srt", PlayerSubtitles.extensionFor(PlayerSubtitles.MIME_SUBRIP));
        check("extension vtt", "vtt", PlayerSubtitles.extensionFor(PlayerSubtitles.MIME_VTT));
        check("stored mime srt", "text/srt", PlayerSubtitles.storedMimeFor(PlayerSubtitles.MIME_SUBRIP));

        // Playback speed: the sheet's choices, slowest first, normal among them.
        boolean ascending = true;
        for (int i = 1; i < PlaybackSpeeds.SPEEDS.length; i++) {
            ascending &= PlaybackSpeeds.SPEEDS[i] > PlaybackSpeeds.SPEEDS[i - 1];
        }
        check("speeds slowest first", true, ascending);
        check("0.5x and 0.75x offered", true, PlaybackSpeeds.SPEEDS[PlaybackSpeeds.nearestIndex(0.5f)] == 0.5f
                && PlaybackSpeeds.SPEEDS[PlaybackSpeeds.nearestIndex(0.75f)] == 0.75f);
        check("normal is a choice", 1f, PlaybackSpeeds.SPEEDS[PlaybackSpeeds.nearestIndex(PlaybackSpeeds.NORMAL)]);
        check("a speed off the list marks the nearest", 1.5f,
                PlaybackSpeeds.SPEEDS[PlaybackSpeeds.nearestIndex(1.6f)]);
        check("a tie marks the slower", 0.5f,
                PlaybackSpeeds.SPEEDS[PlaybackSpeeds.nearestIndex(0.625f)]);
        check("label 0.75", "0.75\u00D7", PlaybackSpeeds.label(0.75f, Locale.US));
        check("label 0.25", "0.25\u00D7", PlaybackSpeeds.label(0.25f, Locale.US));
        check("label 1.5", "1.5\u00D7", PlaybackSpeeds.label(1.5f, Locale.US));
        check("label 2, no decimals", "2\u00D7", PlaybackSpeeds.label(2f, Locale.US));
        check("label 0.75, German comma", "0,75\u00D7", PlaybackSpeeds.label(0.75f, Locale.GERMANY));
        check("label 1.25, Spanish comma", "1,25\u00D7", PlaybackSpeeds.label(1.25f, new Locale("es", "ES")));

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
