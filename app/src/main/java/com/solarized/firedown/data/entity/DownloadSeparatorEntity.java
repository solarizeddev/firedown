package com.solarized.firedown.data.entity;

/**
 * Represents a section header/separator inserted between download items.
 * Used by PagingDataTransforms.insertSeparators to group downloads
 * by date range, size range, or alphabetical first letter depending on sort mode.
 *
 * When sortType is SORT_DATE, titleResId holds a string resource (R.string.*).
 * When sortType is SORT_SIZE or SORT_ALPHABET, titleText holds a plain string label.
 */
public class DownloadSeparatorEntity {

    private int titleResId;   // For date-based headers (uses R.string.*)
    private String titleText; // For size/alphabet headers (plain text like "A", "> 1 GB")
    private int category;     // Unique category id for DiffUtil comparison

    public int getTitleResId() { return titleResId; }
    public void setTitleResId(int titleResId) { this.titleResId = titleResId; }

    public String getTitleText() { return titleText; }
    public void setTitleText(String titleText) { this.titleText = titleText; }

    public int getCategory() { return category; }
    public void setCategory(int category) { this.category = category; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DownloadSeparatorEntity that)) return false;
        return category == that.category
                && titleResId == that.titleResId
                && java.util.Objects.equals(titleText, that.titleText);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(titleResId, titleText, category);
    }
}