package com.solarized.firedown.data.entity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.solarized.firedown.utils.DataUris;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Base64;
import java.util.Random;

/**
 * {@link ContextElementEntity} with a {@code data:} image: the copy a Bundle
 * carries keeps every data: URL's header only, and the tab's whole element is
 * recognised as the one that copy was cut from. Built through the Parcel
 * constructor (the public one takes a live Gecko element).
 *
 * <p>Run from Android Studio (right-click → Debug, debug variant) or:
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.data.entity.ContextElementEntityTest
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class ContextElementEntityTest {

    private static final int TYPE_IMAGE = 1;
    private static final int TYPE_VIDEO = 2;
    private static final String PAGE = "https://example.test/article";
    private static final String HEADER = "data:image/png;base64,";

    private static String inlineImage(int payloadBytes) {
        byte[] bytes = new byte[payloadBytes];
        new Random(3).nextBytes(bytes);
        return HEADER + Base64.getEncoder().encodeToString(bytes);
    }

    private static ContextElementEntity element(int type, String base, String link, String src) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(type);
            parcel.writeString("alt");
            parcel.writeString(base);
            parcel.writeString("title");
            parcel.writeString(link);
            parcel.writeString(src);
            parcel.setDataPosition(0);
            return ContextElementEntity.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static int parcelBytes(ContextElementEntity element) {
        Parcel parcel = Parcel.obtain();
        try {
            element.writeToParcel(parcel, 0);
            return parcel.dataSize();
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void theCopyKeepsOnlyTheHeaderOfAnInlineImage() {
        ContextElementEntity whole = element(TYPE_IMAGE, PAGE, null, inlineImage(300 * 1024));
        ContextElementEntity copy = whole.withoutDataPayload();

        assertNotSame(whole, copy);
        assertEquals(HEADER, copy.getSrcUri());
        assertEquals(PAGE, copy.getBaseUri());
        assertEquals(whole.getType(), copy.getType());
        assertEquals(whole.getAltText(), copy.getAltText());
        assertEquals(whole.getTitle(), copy.getTitle());
        assertTrue(copy.lacksDataPayload());
        assertFalse(whole.lacksDataPayload());
        assertTrue("the whole element is unchanged", DataUris.hasPayload(whole.getSrcUri()));
    }

    @Test
    public void theCopyIsSmallEnoughForABundle() {
        ContextElementEntity whole = element(TYPE_IMAGE, PAGE, null, inlineImage(300 * 1024));
        assertTrue(parcelBytes(whole) > 300 * 1024);
        assertTrue(parcelBytes(whole.withoutDataPayload()) < 1024);
    }

    @Test
    public void aLinkAndABaseThatAreInlineImagesAreCutToo() {
        String image = inlineImage(4096);
        // A tab opened on an inline image has it as its base and as the link.
        ContextElementEntity whole = element(TYPE_IMAGE, image, image, image);
        ContextElementEntity copy = whole.withoutDataPayload();
        assertEquals(HEADER, copy.getSrcUri());
        assertEquals(HEADER, copy.getLinkUri());
        assertEquals(HEADER, copy.getBaseUri());
        assertTrue(copy.isStrippedCopyOf(whole));
    }

    @Test
    public void theWholeElementIsRecognisedFromItsCopy() {
        ContextElementEntity whole = element(TYPE_IMAGE, PAGE, PAGE + "#link", inlineImage(8192));
        ContextElementEntity copy = whole.withoutDataPayload();

        assertTrue(copy.isStrippedCopyOf(whole));
        assertTrue("an element with no payload is its own copy", copy.isStrippedCopyOf(copy));
        assertFalse("the whole element is not a copy of its own copy", whole.isStrippedCopyOf(copy));

        ContextElementEntity otherImage = element(TYPE_IMAGE, PAGE, PAGE + "#link", inlineImage(8191));
        assertTrue("two inline images share a header, so the copy fits either",
                copy.isStrippedCopyOf(otherImage));

        ContextElementEntity otherType = element(TYPE_VIDEO, PAGE, PAGE + "#link", inlineImage(8192));
        assertFalse(copy.isStrippedCopyOf(otherType));

        ContextElementEntity otherPage = element(TYPE_IMAGE, "https://example.test/other", PAGE + "#link", inlineImage(8192));
        assertFalse(copy.isStrippedCopyOf(otherPage));

        ContextElementEntity plain = element(TYPE_IMAGE, PAGE, PAGE + "#link", "https://example.test/a.png");
        assertFalse(copy.isStrippedCopyOf(plain));
        assertFalse(copy.isStrippedCopyOf(null));
    }

    @Test
    public void anElementWithoutInlineDataIsReturnedAsIs() {
        ContextElementEntity plain = element(TYPE_IMAGE, PAGE, PAGE + "#link", "https://example.test/a.png");
        assertSame(plain, plain.withoutDataPayload());
        assertFalse(plain.lacksDataPayload());
        assertTrue(plain.isStrippedCopyOf(plain));
    }

    @Test
    public void theCopySurvivesAParcelRoundTrip() {
        ContextElementEntity copy = element(TYPE_IMAGE, PAGE, null, inlineImage(2048)).withoutDataPayload();
        Parcel parcel = Parcel.obtain();
        try {
            copy.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            ContextElementEntity back = ContextElementEntity.CREATOR.createFromParcel(parcel);
            assertEquals(copy.getType(), back.getType());
            assertEquals(copy.getSrcUri(), back.getSrcUri());
            assertEquals(copy.getBaseUri(), back.getBaseUri());
            assertEquals(copy.getLinkUri(), back.getLinkUri());
            assertEquals(copy.getAltText(), back.getAltText());
            assertEquals(copy.getTitle(), back.getTitle());
            assertTrue(back.lacksDataPayload());
        } finally {
            parcel.recycle();
        }
    }
}
