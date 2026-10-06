package com.btv.ui.player

/** What to play next - handed from BrowseRoute to the shared, Activity-scoped PlayerViewModel via MainActivity. */
data class PlayerLaunchRequest(
    val streamUrl: String,
    val contentId: String?,
    val progressType: String,
    val contentName: String,
    val zapList: List<ZapItem>,
    val posterUrl: String? = null,
    val categoryId: String = "",
    val categoryName: String = "",
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonNum: Int? = null,
    /** Live only: every quality of the launched channel, so fallback works from the first second. */
    val liveVariants: List<ZapItem> = emptyList(),
    /** Live only: the quality was picked explicitly for this launch - don't override it with the remembered one. */
    val explicitQuality: Boolean = false
)
