package com.lighthome.tv;

/** One hero slide. Produced by HeroSyncService (in the :sync process), consumed read-only by the UI. */
public final class HeroItem {
    public final String title;
    public final String overview;
    public final String metaLine;       // "2023 - Series - Crime, Drama"
    public final String providerLabel;  // "Netflix" / "Prime Video" / "Apple TV+" (from config `providers=`)
    public final String providerPackage;
    public final int providerAccent;
    public final boolean priority;      // true if this title originates from AppConfig.PRIORITY_COUNTRY
    public final String backdropFile;   // filename inside filesDir/hero/, or null

    public HeroItem(String title, String overview, String metaLine, String providerLabel,
                     String providerPackage, int providerAccent, boolean priority, String backdropFile) {
        this.title = title;
        this.overview = overview;
        this.metaLine = metaLine;
        this.providerLabel = providerLabel;
        this.providerPackage = providerPackage;
        this.providerAccent = providerAccent;
        this.priority = priority;
        this.backdropFile = backdropFile;
    }
}
