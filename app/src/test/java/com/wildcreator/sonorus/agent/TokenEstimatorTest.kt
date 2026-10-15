package com.wildcreator.sonorus.agent

import com.wildcreator.sonorus.domain.agent.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimatorTest {
    @Test fun emptyTextHasNoTokens() { assertEquals(0, TokenEstimator.estimate("")) }
    @Test fun cjkIsConservativePerCharacter() { assertEquals(2, TokenEstimator.estimate("你好")) }
    @Test fun asciiUsesFourCharactersPerToken() {
        assertEquals(2, TokenEstimator.estimate("abcdefgh"))
    }
    @Test fun mixedTextNeverUnderestimatesAsciiBucket() {
        assertTrue(TokenEstimator.estimate("你好abcdefgh") >= 4)
    }
}
