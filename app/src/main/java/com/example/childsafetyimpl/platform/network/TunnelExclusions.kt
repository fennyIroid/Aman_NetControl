package com.example.childsafetyimpl.platform.network

/**
 * Apps deliberately kept outside the tunnel.
 *
 * This exists because of a failure mode that has nothing to do with filtering and everything to
 * do with whether a family keeps the product installed.
 *
 * Banking, payment and government identity apps routinely refuse to run when they can see a VPN.
 * They check `NetworkCapabilities.TRANSPORT_VPN` on the active network, and a great many of them
 * treat its presence as fraud risk and stop at the splash screen. From the parent's side there is
 * no diagnostic: their banking app worked yesterday, they installed a child-safety product, and
 * now it does not. The app gets uninstalled, and every protection goes with it.
 *
 * `VpnService.Builder.addDisallowedApplication` is the answer the platform provides. An excluded
 * app's traffic never enters the tunnel, and — the part that actually matters here — the network
 * that app sees is the underlying one, so `TRANSPORT_VPN` is not set for it and the check that
 * would have failed does not fire.
 *
 * The cost is stated rather than hidden, and it is small in this design: an excluded app's name
 * lookups are not filtered. For a banking app that is close to meaningless, because it talks to
 * its own bank's servers and is not a route to anything this product blocks. It would be a real
 * hole if the tunnel carried all traffic and this list were long, which is one more reason the
 * list is restricted to financial and identity apps and never extended to browsers, social apps
 * or anything a child would use to reach content.
 *
 * Every entry is a package prefix rather than an exact name, because these apps ship regional
 * variants under sibling package names.
 */
object TunnelExclusions {

    /**
     * Financial and identity apps, weighted towards the launch market.
     *
     * Gulf and wider MENA banking apps are listed explicitly rather than relying on a generic
     * rule, because there is no generic rule: package names carry no category information and
     * Android offers no API that says "this is a banking app".
     */
    val financialPrefixes: List<String> = listOf(
        // Global payment and wallet
        "com.paypal",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user",
        "com.westernunion",
        "com.wise.android",
        "com.revolut",
        "com.payoneer",

        // Gulf and MENA banking
        "com.rajhibank",
        "com.alrajhi",
        "sa.alinma",
        "com.riyadbank",
        "com.sabb",
        "com.ncb",
        "com.alahli",
        "com.bankalbilad",
        "com.arabbank",
        "com.emiratesnbd",
        "com.adcb",
        "ae.mashreq",
        "com.fab.retail",
        "com.dib.mobile",
        "com.qnb",
        "com.qib",
        "com.nbk",
        "com.bankmuscat",
        "com.ahlibank",
        "com.banquemisr",
        "com.cib",
        "eg.nbe",
        "com.aub",
        "com.gtbank",

        // Regional wallets and transfer
        "com.stcpay",
        "sa.stcpay",
        "com.barq",
        "com.vodafone.cash",
        "com.fawry",
        "com.telda",

        // Government identity, which fails the same way and matters more
        "sa.gov.absher",
        "ae.uaepass",
        "com.uaepass",
        "qa.gov.moi.metrash",
        "kw.gov.paci",
    )

    /**
     * Whether an app should be kept out of the tunnel.
     *
     * Deliberately a prefix match. `com.emiratesnbd` and `com.emiratesnbd.uae` are the same
     * bank shipping two builds, and a filter that catches one and not the other produces exactly
     * the intermittent failure this list exists to prevent.
     */
    fun isExcluded(packageName: String): Boolean =
        financialPrefixes.any { packageName == it || packageName.startsWith("$it.") }

    /**
     * Resolves the list against what is actually installed.
     *
     * `addDisallowedApplication` throws `NameNotFoundException` for a package that is not present,
     * and one throw abandons the whole tunnel build. So the caller filters first, and the filter
     * needs the installed set — which the manifest's `<queries>` block governs on Android 11 and
     * above. Any package added here must appear there too, or it is invisible and silently never
     * excluded.
     */
    fun installedExclusions(installedPackages: Collection<String>): List<String> =
        installedPackages.filter { isExcluded(it) }
}
