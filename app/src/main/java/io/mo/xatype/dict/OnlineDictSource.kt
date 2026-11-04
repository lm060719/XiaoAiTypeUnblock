package io.mo.xatype.dict

/**
 * Online Rime word lists. Only one can be active: switching replaces the words imported from the
 * previous source. Nothing is bundled, so the upstream licenses apply to what the user downloads.
 */
enum class OnlineDictSource(
    val id: String,
    val title: String,
    val summary: String,
    val repository: String,
    /** GitHub REST endpoint returning one release object. */
    val releaseApi: String,
    val assetName: String,
    /**
     * File names inside the archive; directories are ignored. Not called `entries`: that name is
     * Kotlin's built-in list of enum constants, and call sites silently resolved to it.
     */
    val dictFiles: List<String>,
    /** Rolling tags keep their name, so the asset's update time identifies the version. */
    val rolling: Boolean
) {
    // "latest" is the rolling `nightly` release, whose files are replaced in place; dated releases
    // come only a few times a year. The tag never changes, so the asset time is the version.
    RIME_ICE(
        "ice", "雾凇拼音", "iDvel/rime-ice · 词库 base + ext",
        "iDvel/rime-ice",
        "https://api.github.com/repos/iDvel/rime-ice/releases/latest",
        "cn_dicts.zip", listOf("base.dict.yaml", "ext.dict.yaml"), rolling = true
    ),
    WANXIANG(
        "wanxiang", "万象拼音", "amzxyz/rime-wanxiang-data · 每日更新词库 jichu",
        "amzxyz/rime-wanxiang-data",
        "https://api.github.com/repos/amzxyz/rime-wanxiang-data/releases/tags/dict-nightly",
        "dicts.zip", listOf("jichu.dict.yaml"), rolling = true
    );

    companion object {
        fun byId(id: String?): OnlineDictSource? = entries.firstOrNull { it.id == id }
    }
}
