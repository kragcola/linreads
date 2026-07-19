package dev.readflow.f1

/**
 * Deterministic EPUB package metadata for F1 mixed heading→image page-turn harness.
 *
 * Produces multiple unique heading markers, each immediately followed by a package-local
 * oversized image reference (never a data URI). Device code scans for a viewport-valid
 * boundary among the candidates rather than hardcoding a page number.
 */
data class F1HeadingImageCandidate(
    val index: Int,
    val headingMarker: String,
    val imageHref: String,
    val imageAltMarker: String,
)

data class F1MixedHeadingImageFixture(
    val packagePath: String,
    val chapterHref: String,
    val imageDir: String,
    val candidates: List<F1HeadingImageCandidate>,
    val syntheticImageColorRgb: Int,
    val containerXml: String,
    val contentOpf: String,
    val chapterXhtml: String,
) {
    init {
        require(candidates.size >= MIN_CANDIDATES) {
            "fixture needs at least $MIN_CANDIDATES heading→image candidates for viewport-adaptive selection"
        }
    }

    companion object {
        const val MIN_CANDIDATES: Int = 4
        /** Distinct solid RGB used in the package-local PNG so crop-band sampling can prove image paint. */
        const val DEFAULT_IMAGE_COLOR_RGB: Int = 0xE11D48

        const val DEFAULT_PACKAGE_PATH: String = "OEBPS/content.opf"
        const val DEFAULT_CHAPTER_HREF: String = "chapter.xhtml"
        const val DEFAULT_IMAGE_DIR: String = "images"
        const val HEADING_MARKER_PREFIX: String = "F1-HEAD"
        const val IMAGE_ALT_MARKER_PREFIX: String = "F1-IMG"

        fun build(
            candidateCount: Int = MIN_CANDIDATES + 2,
            packagePath: String = DEFAULT_PACKAGE_PATH,
            chapterHref: String = DEFAULT_CHAPTER_HREF,
            imageDir: String = DEFAULT_IMAGE_DIR,
            imageColorRgb: Int = DEFAULT_IMAGE_COLOR_RGB,
            leadingBodyParagraphs: Int = 8,
            bodyFillerParagraphsBetweenCandidates: Int = 3,
        ): F1MixedHeadingImageFixture {
            require(candidateCount >= MIN_CANDIDATES) {
                "candidateCount must be >= $MIN_CANDIDATES"
            }
            val candidates = (0 until candidateCount).map { index ->
                val padded = index.toString().padStart(2, '0')
                F1HeadingImageCandidate(
                    index = index,
                    headingMarker = "$HEADING_MARKER_PREFIX-$padded",
                    imageHref = "$imageDir/plate-$padded.png",
                    imageAltMarker = "$IMAGE_ALT_MARKER_PREFIX-$padded",
                )
            }
            val bodyLead = (0 until leadingBodyParagraphs).joinToString("\n") { i ->
                "<p>F1 body lead ${i.toString().padStart(3, '0')} keeps pagination stable before mixed candidates.</p>"
            }
            val bodyBetween = (0 until bodyFillerParagraphsBetweenCandidates).joinToString("\n") { i ->
                "<p>F1 body between ${i.toString().padStart(2, '0')} separates candidate groups without images.</p>"
            }
            val candidateBlocks = candidates.joinToString("\n") { candidate ->
                """
                $bodyBetween
                <h2>${candidate.headingMarker}</h2>
                <p><img src="${candidate.imageHref}" alt="${candidate.imageAltMarker}"/></p>
                """.trimIndent()
            }
            val chapterXhtml = """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>F1 Mixed Heading Image</title></head>
                  <body>
                    $bodyLead
                    $candidateBlocks
                    <p>F1 tail paragraph keeps a final text block after the last image candidate.</p>
                  </body>
                </html>
            """.trimIndent()

            val imageItems = candidates.joinToString("\n") { candidate ->
                val id = "img${candidate.index.toString().padStart(2, '0')}"
                """    <item id="$id" href="${candidate.imageHref}" media-type="image/png"/>"""
            }
            val contentOpf = """
                <?xml version="1.0" encoding="utf-8"?>
                <package version="3.0" unique-identifier="uid" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">urn:readflow:f1-mixed-heading-image</dc:identifier>
                    <dc:title>F1 Mixed Heading Image</dc:title>
                    <dc:language>zh</dc:language>
                  </metadata>
                  <manifest>
                    <item id="c0" href="$chapterHref" media-type="application/xhtml+xml"/>
                $imageItems
                  </manifest>
                  <spine>
                    <itemref idref="c0"/>
                  </spine>
                </package>
            """.trimIndent()

            val containerXml = """
                <?xml version="1.0" encoding="utf-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="$packagePath" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent()

            return F1MixedHeadingImageFixture(
                packagePath = packagePath,
                chapterHref = chapterHref,
                imageDir = imageDir,
                candidates = candidates,
                syntheticImageColorRgb = imageColorRgb,
                containerXml = containerXml,
                contentOpf = contentOpf,
                chapterXhtml = chapterXhtml,
            )
        }

        /**
         * Validates structural invariants used by JVM unit tests and device harness setup.
         * Does not depend on viewport geometry — selection among candidates remains device-side.
         */
        fun validateOrdering(fixture: F1MixedHeadingImageFixture): List<String> {
            val failures = mutableListOf<String>()
            if (fixture.candidates.size < MIN_CANDIDATES) {
                failures += "candidate_count_below_min"
            }
            val markers = fixture.candidates.map { it.headingMarker }
            if (markers.size != markers.toSet().size) {
                failures += "duplicate_heading_markers"
            }
            val alts = fixture.candidates.map { it.imageAltMarker }
            if (alts.size != alts.toSet().size) {
                failures += "duplicate_image_alt_markers"
            }
            fixture.candidates.forEach { candidate ->
                if (candidate.imageHref.startsWith("data:", ignoreCase = true)) {
                    failures += "data_uri_image_forbidden(${candidate.imageHref})"
                }
                if (!candidate.imageHref.startsWith("${fixture.imageDir}/")) {
                    failures += "image_not_package_local(${candidate.imageHref})"
                }
                val headingPos = fixture.chapterXhtml.indexOf(candidate.headingMarker)
                val imagePos = fixture.chapterXhtml.indexOf(candidate.imageHref)
                if (headingPos < 0) failures += "heading_marker_missing(${candidate.headingMarker})"
                if (imagePos < 0) failures += "image_href_missing(${candidate.imageHref})"
                if (headingPos >= 0 && imagePos >= 0 && headingPos >= imagePos) {
                    failures += "heading_must_precede_image(${candidate.headingMarker})"
                }
                // Heading must be immediately followed (as sibling content) by the image: no other
                // F1-HEAD marker may appear between this heading and its image.
                if (headingPos >= 0 && imagePos > headingPos) {
                    val between = fixture.chapterXhtml.substring(headingPos, imagePos)
                    val otherHead = fixture.candidates
                        .filter { it.index != candidate.index }
                        .any { between.contains(it.headingMarker) }
                    if (otherHead) {
                        failures += "intervening_heading_before_image(${candidate.headingMarker})"
                    }
                }
            }
            return failures
        }
    }
}
