package com.solarized.firedown.utils;

import com.solarized.firedown.Sorting;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.DownloadSeparatorEntity;

/**
 * Determines the section category for a DownloadEntity based on the current sort mode.
 *
 * - SORT_DATE:     Groups by time range (Today, Yesterday, This Week, This Month, Older)
 *                  using string resource IDs from DateOrganizer.
 * - SORT_SIZE:     Groups by file size buckets (> 1 GB, > 100 MB, > 10 MB, > 1 MB, < 1 MB).
 * - SORT_ALPHABET: Groups by uppercase first letter of file name (A-Z, #).
 *
 * Usage: Create a new instance per paging transform (stateless within a single pass).
 */
public class DownloadSortOrganizer {

    // Size thresholds in bytes
    private static final long ONE_GB = 1_073_741_824L;
    private static final long HUNDRED_MB = 104_857_600L;
    private static final long TEN_MB = 10_485_760L;
    private static final long ONE_MB = 1_048_576L;

    // Size category IDs (arbitrary unique ints, not resource IDs)
    private static final int SIZE_CAT_1GB = 100;
    private static final int SIZE_CAT_100MB = 101;
    private static final int SIZE_CAT_10MB = 102;
    private static final int SIZE_CAT_1MB = 103;
    private static final int SIZE_CAT_SMALL = 104;

    private final int mSortType;
    private final DateOrganizer mDateOrganizer;

    public DownloadSortOrganizer(int sortType) {
        mSortType = sortType;
        mDateOrganizer = (sortType == Sorting.SORT_DATE) ? new DateOrganizer() : null;
    }

    /**
     * Returns a unique category int for the given entity under the current sort mode.
     * Two entities in the same category should NOT have a separator between them.
     */
    public int getCategory(DownloadEntity entity) {
        return switch (mSortType) {
            case Sorting.SORT_SIZE -> getSizeCategory(entity.getFileSize());
            case Sorting.SORT_ALPHABET -> getAlphabetCategory(entity.getFileName());
            case Sorting.SORT_DOMAIN -> getDomainCategory(entity.getOriginUrl(), entity.getFileUrl());
            default -> mDateOrganizer.getCategory(entity.getFileDate());
        };
    }

    /**
     * Builds a DownloadSeparatorEntity for the given category.
     * For date mode it uses a string resource ID; for size/alphabet it uses plain text.
     */
    public DownloadSeparatorEntity createSeparator(int category) {
        DownloadSeparatorEntity separator = new DownloadSeparatorEntity();
        separator.setCategory(category);

        switch (mSortType) {
            case Sorting.SORT_SIZE -> {
                separator.setTitleText(getSizeLabelForCategory(category));
            }
            case Sorting.SORT_ALPHABET -> {
                separator.setTitleText(getAlphabetLabelForCategory(category));
            }
            case Sorting.SORT_DOMAIN -> {
                separator.setTitleText(getDomainLabelForCategory(category));
            }
            default -> {
                int resId = mDateOrganizer.getResIdForCategory(category);
                separator.setTitleResId(resId);
            }
        }

        return separator;
    }

    // --- Date (delegates to DateOrganizer which you already have) ---

    // --- Size ---

    private int getSizeCategory(long fileSize) {
        if (fileSize >= ONE_GB) return SIZE_CAT_1GB;
        if (fileSize >= HUNDRED_MB) return SIZE_CAT_100MB;
        if (fileSize >= TEN_MB) return SIZE_CAT_10MB;
        if (fileSize >= ONE_MB) return SIZE_CAT_1MB;
        return SIZE_CAT_SMALL;
    }

    private String getSizeLabelForCategory(int category) {
        return switch (category) {
            case SIZE_CAT_1GB -> "> 1 GB";
            case SIZE_CAT_100MB -> "> 100 MB";
            case SIZE_CAT_10MB -> "> 10 MB";
            case SIZE_CAT_1MB -> "> 1 MB";
            default -> "< 1 MB";
        };
    }

    // --- Alphabet ---

    private int getAlphabetCategory(String fileName) {
        if (fileName == null || fileName.isEmpty()) return '#';
        char first = Character.toUpperCase(fileName.charAt(0));
        if (Character.isLetter(first)) return first;
        return '#'; // Non-letter characters grouped under '#'
    }

    private String getAlphabetLabelForCategory(int category) {
        if (category == '#') return "#";
        return String.valueOf((char) category);
    }

    // --- Domain ---

    // Maps domain hash → domain string for label lookup within a single paging pass
    private final java.util.HashMap<Integer, String> mDomainLabels = new java.util.HashMap<>();

    private int getDomainCategory(String originUrl, String fileUrl) {
        String url = (originUrl != null && !originUrl.isEmpty()) ? originUrl : fileUrl;
        String domain = WebUtils.getDomainName(url);
        if (domain == null || domain.isEmpty()) domain = "Unknown";
        int hash = domain.toLowerCase(java.util.Locale.ROOT).hashCode();
        mDomainLabels.put(hash, domain);
        return hash;
    }

    private String getDomainLabelForCategory(int category) {
        String label = mDomainLabels.get(category);
        return (label != null) ? label : "Unknown";
    }
}