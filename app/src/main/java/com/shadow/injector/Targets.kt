package com.shadow.injector

/**
 * The three Garena clients Shadow Injector can target.
 *
 * Only one can ever be selected at a time — MainActivity enforces single selection.
 */
data class GameTarget(
    val id: String,
    val label: String,
    val pkg: String,
    val icon: Int,
    /** Some clients spawn the Unity runtime in a sub process; leave null to use the package name. */
    val process: String? = null
) {
    val processName: String get() = process ?: pkg
}

object Targets {

    val FREE_FIRE = GameTarget(
        id = "ff",
        label = "FREE FIRE",
        pkg = "com.dts.freefireth",
        icon = R.drawable.ic_flare
    )

    val FREE_FIRE_MAX = GameTarget(
        id = "ffmax",
        label = "FREE FIRE MAX",
        pkg = "com.dts.freefiremax",
        icon = R.drawable.ic_star
    )

    val FREE_FIRE_ADVANCED = GameTarget(
        id = "ffadv",
        label = "FREE FIRE ADVANCED",
        pkg = "com.dts.freefireadv",
        icon = R.drawable.ic_bolt
    )

    val ALL = listOf(FREE_FIRE, FREE_FIRE_MAX, FREE_FIRE_ADVANCED)
}
