package com.kungsbackacarcommunity.app.feedback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubLinksTest {
    @Test
    fun `accepts only canonical HTTPS carcommunity issue URLs`() {
        assertTrue(isGitHubWebUrl("https://github.com/SebMcCayen/carcommunity/issues/1"))
        assertTrue(isGitHubWebUrl("https://GITHUB.COM/sebmccayen/CARCOMMUNITY/issues/42?ref=app#top"))

        assertFalse(isGitHubWebUrl("http://github.com/SebMcCayen/carcommunity/issues/1"))
        assertFalse(isGitHubWebUrl("https://gist.github.com/SebMcCayen/carcommunity/issues/1"))
        assertFalse(isGitHubWebUrl("https://github.com.evil.test/SebMcCayen/carcommunity/issues/1"))
        assertFalse(isGitHubWebUrl("https://user@github.com/SebMcCayen/carcommunity/issues/1"))
        assertFalse(isGitHubWebUrl("https://github.com:8443/SebMcCayen/carcommunity/issues/1"))
        assertFalse(isGitHubWebUrl("https://github.com/other/repo/issues/1"))
        assertFalse(isGitHubWebUrl("https://github.com/SebMcCayen/carcommunity/pull/1"))
        assertFalse(isGitHubWebUrl("javascript:alert(1)"))
        assertFalse(isGitHubWebUrl("not a url"))
    }
}
