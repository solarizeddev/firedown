package com.solarized.firedown.data;

import androidx.annotation.NonNull;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.repository.ShortCutsDataRepository;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

import android.content.Context;
import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Hook left in place for the DI graph but intentionally no-op now —
 * the home shortcuts grid starts empty on a fresh install and is
 * populated only by the user's own pins ("Add to shortcuts" in the
 * browser menu, the empty-state Add CTA on the home surface). The
 * previous behaviour seeded the grid from {@code assets/db/shortcuts.json}
 * with a hard-coded list of social media sites, which made the
 * landing surface read like a stock new-tab page.
 *
 * <p>Existing users keep whatever they already had in the DB — this
 * is a fresh-install-only change.</p>
 */
public class ShortCutDatabaseCallback extends RoomDatabase.Callback {

    @SuppressWarnings("unused")
    @Inject
    public ShortCutDatabaseCallback(
            @ApplicationContext Context context,
            Provider<ShortCutsDataRepository> repositoryProvider,
            @Qualifiers.DiskIO Executor executor) {
        // Parameters retained so the DI binding in DatabaseModule keeps
        // resolving; nothing to wire up since onCreate no longer seeds.
    }

    @Override
    public void onCreate(@NonNull SupportSQLiteDatabase db) {
        super.onCreate(db);
        // Intentionally empty — see class doc.
    }
}
