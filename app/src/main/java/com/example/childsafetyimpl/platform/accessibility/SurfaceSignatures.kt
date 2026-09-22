package com.example.childsafetyimpl.platform.accessibility

import com.example.childsafetyimpl.domain.model.RestrictedSurface

/**
 * How this app recognises a screen inside another app.
 *
 * Everything here is a statement about someone else's private implementation. View ids and content
 * descriptions are not API, and the apps being matched change them freely. Keeping the signatures
 * as data rather than as branching code is what makes that fragility manageable: when a match rate
 * collapses, the cause is visible in one table, and a signature can be corrected without touching
 * the service that uses it.
 *
 * Each signature carries several independent signals for the same surface, and any one of them is
 * enough. That redundancy is deliberate. Instagram renaming a view id does not break detection
 * while the content description survives, and a label changing does not break it while the class
 * name survives.
 */
object SurfaceSignatures {

    data class Signature(
        val surface: RestrictedSurface,
        val packages: Set<String>,
        /** Fully qualified view ids: the most specific signal, and the most brittle. */
        val viewIds: Set<String> = emptySet(),
        /** Content descriptions and visible labels. Language dependent. */
        val labels: Set<String> = emptySet(),
        /** Activity or fragment class name fragments. */
        val classFragments: Set<String> = emptySet(),
        /**
         * Labels that rule the match out.
         *
         * This is what stops the Instagram signature firing on the ordinary home feed, where the
         * word "Reels" appears in the navigation bar without the child being in Reels.
         */
        val excludeLabels: Set<String> = emptySet(),
    )

    val signatures: List<Signature> = listOf(

        // --- Short-form feeds --------------------------------------------------------------

        Signature(
            surface = RestrictedSurface.SHORT_FORM_FEED,
            packages = setOf("com.google.android.youtube"),
            viewIds = setOf(
                "com.google.android.youtube:id/reel_recycler",
                "com.google.android.youtube:id/reel_player_page_container",
                "com.google.android.youtube:id/reel_player_underlay",
                "com.google.android.youtube:id/shorts_video_container",
                "com.google.android.youtube:id/reel_progress_bar",
            ),
            labels = setOf("Shorts", "Short", "Shorts player", "Watch Shorts"),
            classFragments = setOf("ReelWatch", "ShortsPlayer", "ReelsFragment"),
        ),
        Signature(
            surface = RestrictedSurface.SHORT_FORM_FEED,
            packages = setOf("com.instagram.android", "com.instagram.lite"),
            viewIds = setOf(
                "com.instagram.android:id/clips_viewer_view_pager",
                "com.instagram.android:id/clips_video_container",
                "com.instagram.android:id/clips_viewer_media_container",
                "com.instagram.android:id/clips_swipe_refresh_container",
            ),
            labels = setOf("Reel by", "Reels audio", "Reels player"),
            classFragments = setOf("ClipsViewer", "ReelViewer", "ClipsFragment"),
            // The tab button carries the label "Reels" everywhere in the app, home feed included.
            excludeLabels = setOf("Reels tab"),
        ),
        Signature(
            surface = RestrictedSurface.SHORT_FORM_FEED,
            packages = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            viewIds = setOf(
                "com.zhiliaoapp.musically:id/viewpager",
                "com.zhiliaoapp.musically:id/feed_container",
            ),
            labels = setOf("For You", "Following", "Swipe up for more"),
            classFragments = setOf("FeedFragment", "MainActivity"),
        ),
        Signature(
            surface = RestrictedSurface.SHORT_FORM_FEED,
            packages = setOf("com.facebook.katana", "com.facebook.lite"),
            labels = setOf("Reel by", "Reels player", "Facebook Reels"),
            classFragments = setOf("ReelsViewer", "WatchReels"),
        ),
        Signature(
            surface = RestrictedSurface.SHORT_FORM_FEED,
            packages = setOf("com.snapchat.android"),
            labels = setOf("Spotlight"),
            classFragments = setOf("Spotlight"),
        ),

        // --- Status and Stories ------------------------------------------------------------

        Signature(
            surface = RestrictedSurface.STATUS_STORY,
            packages = setOf("com.whatsapp", "com.whatsapp.w4b"),
            viewIds = setOf(
                "com.whatsapp:id/status_pager",
                "com.whatsapp:id/status_playback_root",
                "com.whatsapp:id/status_details_container",
                "com.whatsapp:id/status_recycler_view",
            ),
            labels = setOf("Status", "Recent updates", "Viewed updates", "My status"),
            classFragments = setOf("StatusPlayback", "StatusesFragment", "StatusDetails"),
        ),
        Signature(
            surface = RestrictedSurface.STATUS_STORY,
            packages = setOf("com.instagram.android", "com.instagram.lite"),
            viewIds = setOf(
                "com.instagram.android:id/reel_viewer_root",
                "com.instagram.android:id/reel_view_pager",
                "com.instagram.android:id/story_viewer_container",
            ),
            labels = setOf("Story by", "Your story", "Add to story"),
            classFragments = setOf("ReelViewerFragment", "StoryViewer"),
        ),
        Signature(
            surface = RestrictedSurface.STATUS_STORY,
            packages = setOf("com.snapchat.android"),
            labels = setOf("Stories", "Discover", "My Story"),
            classFragments = setOf("StoryViewer", "DiscoverFeed"),
        ),
        Signature(
            surface = RestrictedSurface.STATUS_STORY,
            packages = setOf("org.telegram.messenger", "org.thunderdog.challegram"),
            labels = setOf("Stories", "Post a story"),
            classFragments = setOf("StoryViewer", "StoriesController"),
        ),
        Signature(
            surface = RestrictedSurface.STATUS_STORY,
            packages = setOf("com.facebook.katana", "com.facebook.lite"),
            labels = setOf("Story by", "Add to story", "Stories tray"),
            classFragments = setOf("StoryViewer"),
        ),

        // --- AI conversations ---------------------------------------------------------------

        Signature(
            surface = RestrictedSurface.AI_CONVERSATION,
            packages = setOf("com.openai.chatgpt"),
            labels = setOf("Message ChatGPT", "Ask anything", "New chat", "Message"),
            classFragments = setOf("Conversation", "ChatActivity", "MainActivity"),
        ),
        Signature(
            surface = RestrictedSurface.AI_CONVERSATION,
            packages = setOf("com.anthropic.claude"),
            labels = setOf("Reply to Claude", "Message Claude", "New chat"),
            classFragments = setOf("Conversation", "Chat"),
        ),
        Signature(
            surface = RestrictedSurface.AI_CONVERSATION,
            packages = setOf("com.google.android.apps.bard"),
            labels = setOf("Enter a prompt here", "Ask Gemini", "New chat"),
            classFragments = setOf("Conversation", "Chat"),
        ),
        Signature(
            surface = RestrictedSurface.AI_CONVERSATION,
            packages = setOf("ai.character.app", "ai.replika.app"),
            labels = setOf("Type a message", "Say something", "Message"),
            classFragments = setOf("Chat", "Conversation"),
        ),
        Signature(
            surface = RestrictedSurface.AI_CONVERSATION,
            packages = setOf(
                "com.perplexity.app.android",
                "com.deepseek.chat",
                "ai.x.grok",
                "com.quora.android.poe",
                "com.microsoft.bing",
                "com.snapchat.android.myai",
            ),
            labels = setOf("Ask anything", "Message", "New thread", "Ask follow-up"),
            classFragments = setOf("Chat", "Conversation", "Thread"),
        ),

        // --- The system VPN screen -----------------------------------------------------------

        Signature(
            surface = RestrictedSurface.VPN_SETTINGS,
            packages = setOf(
                "com.android.settings",
                "com.samsung.android.settings",
                "com.miui.securitycenter",
            ),
            labels = setOf(
                "VPN",
                "Add VPN profile",
                "Always-on VPN",
                "VPN settings",
                "Private DNS",
            ),
            classFragments = setOf("VpnSettings", "VpnDialog", "PrivateDnsSettings"),
        ),
    )

    private val byPackage: Map<String, List<Signature>> = signatures
        .flatMap { signature -> signature.packages.map { it to signature } }
        .groupBy({ it.first }, { it.second })

    fun forPackage(packageName: String): List<Signature> = byPackage[packageName].orEmpty()

    fun watches(packageName: String): Boolean = byPackage.containsKey(packageName)

    /** The surfaces this app can recognise inside a given package. */
    fun surfacesIn(packageName: String): Set<RestrictedSurface> =
        forPackage(packageName).map { it.surface }.toSet()

    /** Total number of distinct signals being watched for, shown on the protection screens. */
    val totalSignalCount: Int =
        signatures.sumOf { it.viewIds.size + it.labels.size + it.classFragments.size }

    fun signalCountFor(surface: RestrictedSurface): Int = signatures
        .filter { it.surface == surface }
        .sumOf { it.viewIds.size + it.labels.size + it.classFragments.size }

    fun packagesFor(surface: RestrictedSurface): Set<String> = signatures
        .filter { it.surface == surface }
        .flatMap { it.packages }
        .toSet()
}
