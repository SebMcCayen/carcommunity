package com.kungsbackacarcommunity.app.navigation.turnbyturn

/**
 * Pure (Android-free, SDK-free) geometry and visibility rules for the
 * turn-by-turn screen's chrome and for the follow camera's padding.
 *
 * Same reason [NavHandoff] and [NavProgressFormat] are pure: everything under
 * `src/nav` needs the Mapbox **Navigation** SDK and a `MAPBOX_DOWNLOADS_TOKEN`
 * to compile, so it can only be built by CI's `nav-variant-compile` job and can
 * never be unit-tested. The arithmetic that decides WHERE the puck sits does not
 * need any of that, so it lives here, in `src/main`, with tests — and the
 * navigation screen only calls it.
 *
 * The three rules below are deliberately in ONE file because they are one
 * decision. The camera's top padding is the height of the chrome above the map;
 * shrinking the maneuver banner or hiding the destination bar changes that
 * height, and a padding that did not follow would slide the puck vertically the
 * moment either happened.
 */

/**
 * The compact maneuver banner's measurements.
 *
 * The Mapbox `MapboxManeuverView` ships at roughly [DEFAULT_HEIGHT_DP]: a 48 dp
 * turn icon with a 12 dp top margin, a 22 sp step-distance line under it, 30 sp
 * primary and 24 sp secondary instruction text, and 4 dp of layout padding all
 * round (see `mapbox_main_maneuver_layout.xml` / `styles-maneuver.xml` in
 * `com.mapbox.navigationcore:ui-components`). On a phone that is a card roughly
 * a sixth of the screen tall for one line of text.
 *
 * The banner is NOT replaced — every value here is pushed into the SDK's own
 * view through its public styling API (`updateManeuverViewOptions` +
 * the turn icon's layout params), so the SDK keeps owning what the banner says,
 * how it renders exit numbers, shields and lane guidance, and how it updates.
 * We only make it smaller.
 */
object NavManeuverCompact {
    /** Turn-icon side, down from the SDK's 48 dp. */
    const val TURN_ICON_DP: Int = 32

    /** Turn-icon top margin, down from the SDK's 12 dp. */
    const val TURN_ICON_TOP_MARGIN_DP: Int = 4

    /**
     * Approximate rendered height of the SDK's banner at its default styling,
     * kept only as the baseline [HEIGHT_DP] is measured against — nothing reads
     * it at runtime. 4 (padding) + 12 (icon margin) + 48 (icon) + 42 (22 sp step
     * distance with its padding and margin) + 4 (padding).
     */
    const val DEFAULT_HEIGHT_DP: Double = 110.0

    /**
     * Approximate rendered height of the banner once compacted: 4 (padding) +
     * [TURN_ICON_TOP_MARGIN_DP] + [TURN_ICON_DP] + 31 (14 sp step distance with
     * its padding and margin) + 4 (padding).
     *
     * Approximate on purpose. It feeds the CAMERA's top padding, which only has
     * to reserve about the right amount of screen for the banner; being a few dp
     * out moves the puck a few dp. It is not, and must not become, a layout
     * constraint on the banner itself — the banner stays `wrap_content` so a two
     * line instruction or a sub-maneuver still renders in full.
     */
    const val HEIGHT_DP: Double = 76.0
}

/**
 * Camera padding for one navigation frame, in dp (the caller multiplies by the
 * display density — `EdgeInsets` takes device pixels).
 *
 * The field order is deliberately the same as `com.mapbox.maps.EdgeInsets`'s
 * constructor (top, left, bottom, right) so the conversion at the call site
 * cannot silently transpose two of them.
 */
data class NavCameraPaddingDp(
    val top: Double,
    val left: Double,
    val bottom: Double,
    val right: Double,
) {
    init {
        require(top.isFinite() && top >= 0.0) { "top must be finite and >= 0, was $top" }
        require(left.isFinite() && left >= 0.0) { "left must be finite and >= 0, was $left" }
        require(bottom.isFinite() && bottom >= 0.0) { "bottom must be finite and >= 0, was $bottom" }
        require(right.isFinite() && right >= 0.0) { "right must be finite and >= 0, was $right" }
    }

    /**
     * Whether this padding leaves the framed point HORIZONTALLY CENTRED.
     *
     * The follow camera places the framed point at a focal point expressed as a
     * fraction of the padded box, so equal left/right padding — and only equal
     * left/right padding — puts it on the screen's vertical centre line. This is
     * the property the tests pin, because an asymmetric side padding is the
     * classic way a navigation puck ends up sitting off to one side.
     */
    val horizontallyCentred: Boolean get() = left == right
}

/**
 * The follow/overview camera padding, derived from the chrome actually on screen.
 *
 * ## The reported bug
 * "The screen is not centering on my location — my location is a little bit to
 * the right, even when pressing the GPS or north button." The padding was
 * already symmetric, so the sideways drift was not coming from here; it came
 * from the SDK's framing rules (see the navigation screen's `init`, where
 * `maximizeViewableGeometryWhenPitchZero` is switched off). What this object
 * fixes is the OTHER half: the padding was a set of hardcoded numbers that
 * described chrome sizes nobody was checking, so shrinking the maneuver banner
 * or hiding the destination bar would have left the puck drifting vertically
 * instead.
 */
object NavCameraPadding {
    /**
     * Side padding, applied EQUALLY left and right.
     *
     * One constant used for both, rather than two that happen to be equal: the
     * horizontal centring of the puck is exactly the statement "these two are
     * the same number", and a single constant makes that unbreakable.
     */
    const val SIDE_DP: Double = 40.0

    /**
     * The strip above the maneuver banner: the status-bar inset the top column
     * clears plus the column's own 12 dp padding. A fixed estimate because the
     * engine that applies this padding has no window insets — it holds a
     * `MapView`, not a composition.
     */
    const val STATUS_CHROME_DP: Double = 32.0

    /**
     * The destination ("search result") pill above the banner, plus the 8 dp gap
     * under it. Only counted while the pill is actually shown — see
     * [NavTopChrome.destinationBarVisible].
     */
    const val DESTINATION_BAR_DP: Double = 48.0

    /**
     * The bottom chrome: the ETA/exit bar, the speed readout and control stack
     * above it, and the navigation-bar inset the bar sits in.
     */
    const val BOTTOM_CHROME_DP: Double = 160.0

    /**
     * Padding for the FOLLOWING frame — the one the driver spends the whole trip
     * looking at.
     *
     * @param maneuverBannerHeightDp what the maneuver banner currently occupies
     *   ([NavManeuverCompact.HEIGHT_DP] in production).
     * @param destinationBarVisible whether the destination pill is on screen.
     */
    fun following(
        maneuverBannerHeightDp: Double,
        destinationBarVisible: Boolean,
    ): NavCameraPaddingDp =
        NavCameraPaddingDp(
            top = topChromeDp(maneuverBannerHeightDp, destinationBarVisible),
            left = SIDE_DP,
            bottom = BOTTOM_CHROME_DP,
            right = SIDE_DP,
        )

    /**
     * Padding for the OVERVIEW frame.
     *
     * Identical to [following], deliberately: overview reframes WHAT is shown,
     * it does not remove any chrome, so the same strips of screen are covered
     * and the same reservations apply. The two used to differ by 40 dp at the
     * top for no stated reason, which meant a route framed in overview sat
     * higher than the space the banner actually needed.
     */
    fun overview(
        maneuverBannerHeightDp: Double,
        destinationBarVisible: Boolean,
    ): NavCameraPaddingDp = following(maneuverBannerHeightDp, destinationBarVisible)

    /** Total chrome above the map, in dp. */
    fun topChromeDp(
        maneuverBannerHeightDp: Double,
        destinationBarVisible: Boolean,
    ): Double {
        require(maneuverBannerHeightDp.isFinite() && maneuverBannerHeightDp >= 0.0) {
            "maneuverBannerHeightDp must be finite and >= 0, was $maneuverBannerHeightDp"
        }
        val bar = if (destinationBarVisible) DESTINATION_BAR_DP else 0.0
        return STATUS_CHROME_DP + maneuverBannerHeightDp + bar
    }
}

/** Visibility rules for the navigation screen's TOP chrome. */
object NavTopChrome {
    /**
     * Whether the destination pill — the rounded bar in the upper-left carrying
     * the searched-for place name and the back arrow — should be composed.
     *
     * Shown only BEFORE guidance is running, and hidden for the whole of an
     * active navigation. The reported complaint was precisely that: "if you have
     * searched for the place and started the navigation, you will still see the
     * search result in the upper left corner… there is no need to see it".
     * Before the first route-progress tick there is no maneuver banner and no
     * ETA yet, so the pill is the only thing naming where the user is going and
     * the only visible way back out — which is why it is hidden on guidance
     * starting rather than on the screen opening.
     *
     * Leaving navigation is unaffected either way: the ETA bar's Exit button,
     * the system back gesture and the back handler all still work, and all three
     * exist independently of this pill.
     *
     * @param guidanceActive whether route progress is ticking (the SDK is
     *   actively guiding), which is also what makes the maneuver banner appear.
     */
    fun destinationBarVisible(guidanceActive: Boolean): Boolean = !guidanceActive
}
