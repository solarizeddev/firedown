package com.solarized.firedown.manager;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link UrlParser#parseFilePath}: the collision counter goes on the file
 * NAME, before its extension, whatever the folder is called. The engine's
 * collision loop calls it until the path is free, so a path it returns
 * unchanged spins that loop forever.
 */
public class UrlParserFilePathTest {

    @Test
    public void firstCounterGoesBeforeTheExtension() {
        assertEquals("/a/b/clip(1).mp4", UrlParser.parseFilePath("/a/b/clip.mp4"));
        assertEquals("clip(1).mp4", UrlParser.parseFilePath("clip.mp4"));
    }

    @Test
    public void anExistingCounterIsIncremented() {
        assertEquals("/a/b/clip(2).mp4", UrlParser.parseFilePath("/a/b/clip(1).mp4"));
        assertEquals("/a/b/clip(10).mp4", UrlParser.parseFilePath("/a/b/clip(9).mp4"));
        assertEquals("/x/name (2).mp4", UrlParser.parseFilePath("/x/name (1).mp4"));
    }

    @Test
    public void aNameWithoutAnExtensionGetsTheCounterAtItsEnd() {
        assertEquals("/x/clip(1)", UrlParser.parseFilePath("/x/clip"));
        assertEquals("/x/clip(2)", UrlParser.parseFilePath("/x/clip(1)"));
    }

    @Test
    public void aDottedFolderNeverTakesTheCounter() {
        // An app-private folder carries the package name's dots; the counter
        // used to land inside it ("com.solarized(1).firedown/...") or nowhere.
        String folder = "/data/user/0/com.solarized.firedown/files/safe/";
        assertEquals(folder + "clip(1)", UrlParser.parseFilePath(folder + "clip"));
        assertEquals(folder + "clip(1).mp4", UrlParser.parseFilePath(folder + "clip.mp4"));
        assertEquals(folder + "clip(3).mp4", UrlParser.parseFilePath(folder + "clip(2).mp4"));
    }

    @Test
    public void aLeadingDotIsNotAnExtension() {
        assertEquals("/x/.hidden(1)", UrlParser.parseFilePath("/x/.hidden"));
    }

    @Test
    public void onlyTheLastDotIsTheExtension() {
        assertEquals("/x/a.b.c(1).mp4", UrlParser.parseFilePath("/x/a.b.c.mp4"));
    }

    @Test
    public void bracketsThatAreNotACounterStay() {
        assertEquals("/x/clip(a)(1).mp4", UrlParser.parseFilePath("/x/clip(a).mp4"));
        assertEquals("/x/(2).mp4", UrlParser.parseFilePath("/x/(1).mp4"));
    }

    @Test
    public void everyCallMovesThePath() {
        String path = "/x/clip.mp4";
        for (int i = 0; i < 5; i++) {
            String next = UrlParser.parseFilePath(path);
            if (next.equals(path)) {
                throw new AssertionError("unchanged after " + i + " calls: " + path);
            }
            path = next;
        }
        assertEquals("/x/clip(5).mp4", path);
    }
}
