package com.example.childsafetyimpl.data.source

import com.example.childsafetyimpl.domain.model.AppCategory

/**
 * The curated safety list.
 *
 * Android has no signal for "this is an AI app" and only a coarse one for social versus messaging,
 * so classification leans on this table. It is the honest weak point of the protections that
 * depend on it: coverage decays as new apps appear, which is why the app also reports how many
 * installed apps it could not classify rather than quietly treating them as safe.
 *
 * Every package listed here must also appear in the manifest's queries block, or Android 11+
 * package visibility filtering hides it even from a targeted lookup.
 */
object KnownPackages {

    val social: Set<String> = setOf(
        "com.instagram.android",
        "com.instagram.lite",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.zhiliaoapp.musically",      // TikTok
        "com.ss.android.ugc.trill",      // TikTok (regional)
        "com.snapchat.android",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.pinterest",
        "com.linkedin.android",
        "sh.whisper",
        "com.tumblr",
        "com.vkontakte.android",
    )

    val communication: Set<String> = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thunderdog.challegram",
        "com.facebook.orca",
        "com.facebook.mlite",
        "com.discord",
        "com.viber.voip",
        "jp.naver.line.android",
        "com.imo.android.imoim",
        "com.skype.raider",
        "org.thoughtcrime.securesms",    // Signal
        "kik.android",
        "com.microsoft.teams",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
    )

    val ai: Set<String> = setOf(
        "com.openai.chatgpt",
        "com.anthropic.claude",
        "com.google.android.apps.bard",       // Gemini
        "com.microsoft.bing",
        "ai.character.app",                    // Character.AI
        "ai.replika.app",
        "com.perplexity.app.android",
        "com.jina.chat",
        "ai.x.grok",
        "com.deepseek.chat",
        "com.quora.android.poe",
        "com.snapchat.android.myai",
    )

    val video: Set<String> = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",
        "com.google.android.youtube.tv",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "com.disney.disneyplus",
        "in.startv.hotstar",
        "com.mxtech.videoplayer.ad",
    )

    val browser: Set<String> = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.duckduckgo.mobile.android",
        "com.UCMobile.intl",
        "com.sec.android.app.sbrowser",
        "com.kiwibrowser.browser",
    )

    /**
     * Cloning and parallel-space tools.
     *
     * Presence of any of these is a bypass signal in itself: their entire purpose is to run a
     * second copy of an app outside whatever is watching the first one.
     */
    val cloneTools: Set<String> = setOf(
        "com.lbe.parallel.intl",              // Parallel Space
        "com.lbe.parallel",
        "com.excelliance.dualaid",            // Dual Space
        "com.ludashi.dualspace",
        "com.jide.repackaged.andcloneapp",
        "com.applisto.appcloner",             // App Cloner
        "com.parallel.space.lite",
        "com.polestar.super.clone",
        "multi.parallel.dualspace.cloner",
        "com.dualspace.multispace.android",
        "do.multiple.space.parallel",
        "com.cloneapp.parallelspace.dualspace",
    )

    /** VPN clients a child would install to route around the tunnel. */
    val vpnTools: Set<String> = setOf(
        "com.nordvpn.android",
        "com.expressvpn.vpn",
        "com.protonvpn.android",
        "hotspotshield.android.vpn",
        "com.surfshark.vpnclient.android",
        "de.blinkt.openvpn",
        "net.openvpn.openvpn",
        "com.psiphon3",
        "org.torproject.torbrowser",
        "com.turbovpn.vpn.free",
        "free.vpn.unblock.proxy.turbovpn",
        "com.fast.free.unblock.thunder.vpn",
    )

    val education: Set<String> = setOf(
        "com.duolingo",
        "org.khanacademy.android",
        "com.google.android.apps.classroom",
        "com.byjus.thelearningapp",
        "com.photomath.app",
        "com.quizlet.quizletandroid",
    )

    /** Games that carry no declared platform category often still appear here. */
    val games: Set<String> = setOf(
        "com.roblox.client",
        "com.mojang.minecraftpe",
        "com.epicgames.fortnite",
        "com.dts.freefireth",
        "com.tencent.ig",                     // PUBG Mobile
        "com.activision.callofduty.shooter",
        "com.supercell.clashofclans",
        "com.supercell.brawlstars",
        "com.innersloth.spacemafia",          // Among Us
        "com.king.candycrushsaga",
        "com.miniclip.eightballpool",
        "com.garena.game.kgvn",
    )

    private val lookup: Map<String, AppCategory> = buildMap {
        cloneTools.forEach { put(it, AppCategory.CLONE_TOOL) }
        vpnTools.forEach { put(it, AppCategory.VPN_TOOL) }
        ai.forEach { put(it, AppCategory.AI) }
        communication.forEach { put(it, AppCategory.COMMUNICATION) }
        social.forEach { put(it, AppCategory.SOCIAL) }
        video.forEach { put(it, AppCategory.VIDEO) }
        browser.forEach { put(it, AppCategory.BROWSER) }
        education.forEach { put(it, AppCategory.EDUCATION) }
        games.forEach { put(it, AppCategory.GAME) }
    }

    /** All packages this app declares an interest in, used by the visibility probe. */
    val allDeclared: Set<String> = lookup.keys

    fun categoryOf(packageName: String): AppCategory? = lookup[packageName]

    fun isCloneTool(packageName: String): Boolean = packageName in cloneTools

    fun isVpnTool(packageName: String): Boolean = packageName in vpnTools

    fun isAi(packageName: String): Boolean = packageName in ai

    fun isGame(packageName: String): Boolean = packageName in games

    /** Apps that carry a short-form feed the short-form control watches for. */
    val shortFormApps: Set<String> = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.instagram.lite",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.snapchat.android",
    )

    /** Apps carrying a Status, Stories or disappearing-post surface. */
    val statusApps: Set<String> = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.instagram.android",
        "com.instagram.lite",
        "com.snapchat.android",
        "org.telegram.messenger",
        "com.facebook.katana",
    )

    /**
     * Readable names for apps that may not be installed.
     *
     * A package name's last segment is a poor label: com.instagram.lite becomes "lite" and
     * com.ss.android.ugc.trill becomes "trill", which tells a parent nothing about what is being
     * watched for. Android can only supply a real label for an app that is actually present, so
     * the ones this app names in its own lists are named here too.
     */
    private val displayNames: Map<String, String> = mapOf(
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.youtube.kids" to "YouTube Kids",
        "com.instagram.android" to "Instagram",
        "com.instagram.lite" to "Instagram Lite",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.facebook.katana" to "Facebook",
        "com.facebook.lite" to "Facebook Lite",
        "com.facebook.orca" to "Messenger",
        "com.snapchat.android" to "Snapchat",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.thunderdog.challegram" to "Telegram X",
        "com.twitter.android" to "X",
        "com.reddit.frontpage" to "Reddit",
        "com.discord" to "Discord",
        "org.thoughtcrime.securesms" to "Signal",
        "com.openai.chatgpt" to "ChatGPT",
        "com.anthropic.claude" to "Claude",
        "com.google.android.apps.bard" to "Gemini",
        "ai.character.app" to "Character.AI",
        "ai.replika.app" to "Replika",
        "com.perplexity.app.android" to "Perplexity",
        "com.deepseek.chat" to "DeepSeek",
        "ai.x.grok" to "Grok",
        "com.quora.android.poe" to "Poe",
        "com.microsoft.bing" to "Bing",
        "com.snapchat.android.myai" to "My AI",
        "com.android.settings" to "Android Settings",
        "com.samsung.android.settings" to "Samsung Settings",
        "com.miui.securitycenter" to "Xiaomi Security",
    )

    /** A readable name for a package, whether or not it is installed. */
    fun displayNameFor(packageName: String): String =
        displayNames[packageName] ?: packageName

    /** The system settings packages whose VPN screen the VPN guard closes. */
    val settingsPackages: Set<String> = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
    )
}
