package org.fossify.gallery

import org.junit.Assert.assertTrue
import org.junit.Test

// The label the foot of the settings screen shows is put together by gradle from git, see
// BUILD_LABEL in app/build.gradle.kts. What is pinned here is its shape, since the value is
// different on every build: a release tag alone on a tagged commit, otherwise the nearest tag
// with the distance and the sha (and "-dirty"), the branch, and when it was built
class BuildLabelTest {
    private val releaseTag = Regex("""^v\d+\.\d+\.\d+$""")
    private val betweenReleases = Regex("""^v\d+\.\d+\.\d+-\d+-g[0-9a-f]+(-dirty)? · [^·]+ · \d{4}-\d\d-\d\d \d\d:\d\d$""")

    @Test
    fun `the label is a release tag, or the nearest tag with the branch and the build time`() {
        val label = BuildConfig.BUILD_LABEL
        assertTrue("\"$label\" is neither shape", releaseTag.matches(label) || betweenReleases.matches(label))
    }
}
