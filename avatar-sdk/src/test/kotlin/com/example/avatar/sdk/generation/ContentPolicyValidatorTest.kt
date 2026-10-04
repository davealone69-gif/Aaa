package com.example.avatar.sdk.generation

import com.example.avatar.sdk.api.AvatarRequest
import com.example.avatar.sdk.api.ContentPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentPolicyValidatorTest {
    @Test fun sexualPromptRequiresAdultMode() {
        val error = catch { ContentPolicyValidator.validate(AvatarRequest(prompt = "adult nude portrait")) }
        assertEquals("ADULT_MODE_REQUIRED", error.code)
    }

    @Test fun adultModeRequiresExplicitAgeGate() {
        val error = catch { ContentPolicyValidator.validate(AvatarRequest(prompt = "adult nude portrait", policy = ContentPolicy(adultModeEnabled = true))) }
        assertEquals("AGE_GATE_REQUIRED", error.code)
    }

    @Test fun ambiguousSexualAgeIsRejected() {
        val policy = ContentPolicy(adultModeEnabled = true, ageGateConfirmed = true)
        val error = catch { ContentPolicyValidator.validate(AvatarRequest(prompt = "youthful nude adult portrait", policy = policy)) }
        assertEquals("AMBIGUOUS_ADULT_AGE", error.code)
    }

    @Test fun explicitAdultSexualPromptCanPassSdkPolicy() {
        val policy = ContentPolicy(adultModeEnabled = true, ageGateConfirmed = true)
        ContentPolicyValidator.validate(AvatarRequest(prompt = "18+ adult nude portrait", policy = policy))
    }

    @Test fun minorTermsAreAlwaysRejected() {
        val policy = ContentPolicy(adultModeEnabled = true, ageGateConfirmed = true)
        val error = catch { ContentPolicyValidator.validate(AvatarRequest(prompt = "18+ adult portrait of a teenager", policy = policy)) }
        assertEquals("MINOR_OR_UNDERAGE_REQUEST", error.code)
    }

    @Test fun adultModePhotoIsBlockedAndNormalPhotoNeedsConsent() {
        val adult = catch { ContentPolicyValidator.validateReferencePhoto(ContentPolicy(adultModeEnabled = true, ageGateConfirmed = true)) }
        assertEquals("PHOTO_DISABLED_IN_ADULT_MODE", adult.code)
        val normal = catch { ContentPolicyValidator.validateReferencePhoto(ContentPolicy()) }
        assertEquals("PHOTO_CONSENT_REQUIRED", normal.code)
    }

    private fun catch(block: () -> Unit): ContentPolicyException = try { block(); error("Expected ContentPolicyException") } catch (e: ContentPolicyException) { e }
}
