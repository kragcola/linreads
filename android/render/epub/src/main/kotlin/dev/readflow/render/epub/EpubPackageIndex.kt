package dev.readflow.render.epub

internal data class EpubManifestItem(
    val id: String,
    val path: String,
    val mediaType: String,
    val properties: Set<String>,
)

internal data class EpubPackageSpineItem(
    val manifestItem: EpubManifestItem,
    val uncompressedSizeBytes: Long,
) {
    val path: String get() = manifestItem.path
}

internal data class EpubPackageTocDocument(
    val path: String,
    val entries: List<EpubParsedTocEntry>,
)

internal data class EpubPackageIndex(
    val opfPath: String,
    val manifestItems: Map<String, EpubManifestItem>,
    val spineItems: List<EpubPackageSpineItem>,
    val navDocument: EpubPackageTocDocument?,
    val ncxDocument: EpubPackageTocDocument?,
    val isFixedLayout: Boolean,
) {
    val spinePaths: List<String> get() = spineItems.map(EpubPackageSpineItem::path)
    val spineEntryWeights: List<Long> get() = spineItems.map(EpubPackageSpineItem::uncompressedSizeBytes)

    companion object {
        fun empty(): EpubPackageIndex =
            EpubPackageIndex(
                opfPath = "",
                manifestItems = emptyMap(),
                spineItems = emptyList(),
                navDocument = null,
                ncxDocument = null,
                isFixedLayout = false,
            )
    }
}
