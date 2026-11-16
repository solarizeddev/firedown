package com.solarized.firedown.manager;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Keys;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * The one way a list of selected items ({@link Keys#ITEM_LIST_ID}) travels
 * between screens and services.
 *
 * <p>An Intent crosses Binder, whose buffer is 1 MB for the whole process,
 * and a fragment's arguments are written into the activity's saved state,
 * which crosses it too. A download row carries signed URLs and header JSON,
 * so a few thousand selected rows do not fit: shipped as
 * {@code TransactionTooLargeException: data parcel size 1872632 bytes} out of
 * the multi-select Lock. So a list rides the Intent or Bundle only while its
 * parcel is under {@link #MAX_INLINE_BYTES}; a bigger one is PARKED here, in
 * the process every sender and reader share, and only its token travels
 * ({@link Keys#ITEM_LIST_TOKEN}). A small list keeps the exact shape it always
 * had, so a reader of the old shape still reads it.</p>
 *
 * <p>Reading does not consume: a dialog recreated after a rotation reads its
 * arguments again, and a token is forwarded from hop to hop (the list, the
 * lock screen, its result, the service). Every read returns fresh copies, as
 * an unparcelled Intent extra would, so a task that changes the entities it is
 * handed never changes the ones a list on screen still shows. The park is
 * bounded in COUNT, the bound memory needs. A token that outlived the process
 * (a screen restored after a death) reads as an empty list, which every reader
 * already treats as "nothing to do".</p>
 */
public final class ItemLists {

    private static final String TAG = ItemLists.class.getSimpleName();

    /** Largest list parcel carried inline; Binder's buffer is 1 MB, shared. */
    static final int MAX_INLINE_BYTES = 128 * 1024;

    /** Parked lists kept for readers that have not read them yet. */
    static final int MAX_PARKED = 8;

    private static final LinkedHashMap<String, Parked> sParked = new LinkedHashMap<>();

    private ItemLists() {
    }

    private static final class Parked {
        final ArrayList<? extends Parcelable> items;
        final ClassLoader loader;

        Parked(ArrayList<? extends Parcelable> items, ClassLoader loader) {
            this.items = items;
            this.loader = loader;
        }
    }

    public static void put(@NonNull Intent intent, @NonNull ArrayList<? extends Parcelable> list) {
        String token = parkIfLarge(list);
        if (token == null) {
            intent.putParcelableArrayListExtra(Keys.ITEM_LIST_ID, list);
        } else {
            intent.putExtra(Keys.ITEM_LIST_TOKEN, token);
        }
    }

    public static void put(@NonNull Bundle bundle, @NonNull ArrayList<? extends Parcelable> list) {
        String token = parkIfLarge(list);
        if (token == null) {
            bundle.putParcelableArrayList(Keys.ITEM_LIST_ID, list);
        } else {
            bundle.putString(Keys.ITEM_LIST_TOKEN, token);
        }
    }

    public static boolean carries(@Nullable Intent intent) {
        return intent != null
                && (intent.hasExtra(Keys.ITEM_LIST_TOKEN) || intent.hasExtra(Keys.ITEM_LIST_ID));
    }

    public static boolean carries(@Nullable Bundle bundle) {
        return bundle != null
                && (bundle.containsKey(Keys.ITEM_LIST_TOKEN) || bundle.containsKey(Keys.ITEM_LIST_ID));
    }

    /** The list the Intent names, by either carrier; empty when it names none. */
    @NonNull
    public static <T extends Parcelable> ArrayList<T> read(@Nullable Intent intent) {
        if (intent == null) {
            return new ArrayList<>();
        }
        String token = intent.getStringExtra(Keys.ITEM_LIST_TOKEN);
        if (token != null) {
            return readParked(token);
        }
        ArrayList<T> list = intent.getParcelableArrayListExtra(Keys.ITEM_LIST_ID);
        return list != null ? list : new ArrayList<>();
    }

    /**
     * {@link #read(Intent)} for the LAST reader of a list (the service that
     * acts on it): a parked list is released, so it does not hold one of the
     * {@link #MAX_PARKED} places until later parks evict it.
     */
    @NonNull
    @SuppressWarnings("unchecked")
    public static <T extends Parcelable> ArrayList<T> take(@Nullable Intent intent) {
        if (intent == null) {
            return new ArrayList<>();
        }
        String token = intent.getStringExtra(Keys.ITEM_LIST_TOKEN);
        if (token == null) {
            return read(intent);
        }
        Parked parked;
        synchronized (sParked) {
            parked = sParked.remove(token);
        }
        if (parked == null) {
            Log.w(TAG, "take: nothing parked for the token (restored after the process died?)");
            return new ArrayList<>();
        }
        // Nobody reads it after this, so the parked copy itself is handed over.
        return new ArrayList<>((ArrayList<T>) parked.items);
    }

    /** The list the Bundle names, by either carrier; empty when it names none. */
    @NonNull
    public static <T extends Parcelable> ArrayList<T> read(@Nullable Bundle bundle,
                                                            @NonNull ClassLoader loader) {
        if (bundle == null) {
            return new ArrayList<>();
        }
        String token = bundle.getString(Keys.ITEM_LIST_TOKEN);
        if (token != null) {
            return readParked(token);
        }
        bundle.setClassLoader(loader);
        ArrayList<T> list = bundle.getParcelableArrayList(Keys.ITEM_LIST_ID);
        return list != null ? list : new ArrayList<>();
    }

    /** Hands the list a Bundle carries on to an Intent, token or inline. */
    public static void forward(@NonNull Bundle from, @NonNull ClassLoader loader, @NonNull Intent to) {
        String token = from.getString(Keys.ITEM_LIST_TOKEN);
        if (token != null) {
            to.putExtra(Keys.ITEM_LIST_TOKEN, token);
            return;
        }
        from.setClassLoader(loader);
        ArrayList<Parcelable> list = from.getParcelableArrayList(Keys.ITEM_LIST_ID);
        if (list != null) {
            to.putParcelableArrayListExtra(Keys.ITEM_LIST_ID, list);
        }
    }

    /** Hands the list one Intent carries on to another, token or inline. */
    public static void forward(@NonNull Intent from, @NonNull Intent to) {
        String token = from.getStringExtra(Keys.ITEM_LIST_TOKEN);
        if (token != null) {
            to.putExtra(Keys.ITEM_LIST_TOKEN, token);
            return;
        }
        ArrayList<Parcelable> list = from.getParcelableArrayListExtra(Keys.ITEM_LIST_ID);
        if (list != null) {
            to.putParcelableArrayListExtra(Keys.ITEM_LIST_ID, list);
        }
    }

    /* Null when the list fits inline; else the token of a parked copy. */
    @Nullable
    private static String parkIfLarge(@NonNull ArrayList<? extends Parcelable> list) {
        if (list.isEmpty()) {
            return null;
        }
        int bytes = parcelSize(list);
        if (bytes <= MAX_INLINE_BYTES) {
            return null;
        }
        ClassLoader loader = list.get(0).getClass().getClassLoader();
        // A copy, so a list the sender keeps changing is not the one parked.
        ArrayList<Parcelable> copy = copyOf(list, loader);
        String token = UUID.randomUUID().toString();
        synchronized (sParked) {
            Iterator<String> oldest = sParked.keySet().iterator();
            while (sParked.size() >= MAX_PARKED && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
            sParked.put(token, new Parked(copy, loader));
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, list.size() + " item(s) parcel to " + bytes
                    + " bytes, handed over in-process");
        }
        return token;
    }

    @NonNull
    @SuppressWarnings("unchecked")
    private static <T extends Parcelable> ArrayList<T> readParked(@NonNull String token) {
        Parked parked;
        synchronized (sParked) {
            parked = sParked.get(token);
        }
        if (parked == null) {
            Log.w(TAG, "read: nothing parked for the token (restored after the process died?)");
            return new ArrayList<>();
        }
        return (ArrayList<T>) copyOf(parked.items, parked.loader);
    }

    /* What the Intent would carry: the list parcelled the way an extra parcels it. */
    static int parcelSize(@NonNull List<? extends Parcelable> list) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeList(list);
            return parcel.dataSize();
        } finally {
            parcel.recycle();
        }
    }

    /* Fresh objects, made the way an unparcelled extra makes them. */
    @NonNull
    @SuppressWarnings({"unchecked", "deprecation"})
    private static ArrayList<Parcelable> copyOf(@NonNull List<? extends Parcelable> list,
                                                @Nullable ClassLoader loader) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeList(list);
            parcel.setDataPosition(0);
            ArrayList<Parcelable> copy = parcel.readArrayList(loader);
            return copy != null ? copy : new ArrayList<>();
        } finally {
            parcel.recycle();
        }
    }
}
