package com.example.avatar.sdk.generation

import com.example.avatar.sdk.api.AvatarRequest

class ContentPolicyException(val code: String, override val message: String) : IllegalArgumentException(message)

/** Application safety boundary; age-gate state is not proof of a person's identity or actual age. */
object ContentPolicyValidator {
    private val minorTerms = Regex("\\b(child|children|minor|underage|preteen|teen|teenage|teenager|adolescent|school[- ]?age|schoolgirl|schoolboy|loli|shota)\\b", RegexOption.IGNORE_CASE)
    private val under18 = Regex("\\b(?:age\\s*)?(?:[0-9]|1[0-7])\\s*(?:year|years|yo|y/o)\\b", RegexOption.IGNORE_CASE)
    private val ambiguousPersonTerms = Regex("\\b(girl|boy|student|fresh[- ]?faced|barely[- ]?legal|young[- ]?looking|youthful)\\b", RegexOption.IGNORE_CASE)
    private val adultContent = Regex("\\b(nsfw|sexual|sexually|explicit|nude|nudity|erotic|pornographic|porn|fetish|intercourse|masturbat(?:e|ion)|genitals?|breasts?|topless|blowjob|anal)\\b", RegexOption.IGNORE_CASE)
    private val explicitAdult = Regex("\\b(adult|18\\s*\\+|18[- ]?year[- ]?old|19|20|21|22|23|24|25|mature)\\b", RegexOption.IGNORE_CASE)

    fun validate(request: AvatarRequest) {
        val prompt = request.prompt.orEmpty()
        if (minorTerms.containsMatchIn(prompt) || under18.containsMatchIn(prompt)) {
            throw ContentPolicyException("MINOR_OR_UNDERAGE_REQUEST", "The request suggests a person under 18 and was blocked before transmission")
        }
        val sexual = adultContent.containsMatchIn(prompt)
        if (sexual && !request.policy.adultModeEnabled) {
            throw ContentPolicyException("ADULT_MODE_REQUIRED", "Sexual content requires the separately enabled adult mode")
        }
        if (request.policy.adultModeEnabled && !request.policy.ageGateConfirmed) {
            throw ContentPolicyException("AGE_GATE_REQUIRED", "Adult mode requires an explicit local 18+ confirmation")
        }
        if (request.policy.adultModeEnabled && sexual && (!explicitAdult.containsMatchIn(prompt) || ambiguousPersonTerms.containsMatchIn(prompt))) {
            throw ContentPolicyException("AMBIGUOUS_ADULT_AGE", "Adult sexual requests must explicitly describe an adult 18+ subject without ambiguous-age wording")
        }
        if (request.image != null) validateReferencePhoto(request.policy)
    }

    fun validateReferencePhoto(policy: com.example.avatar.sdk.api.ContentPolicy) {
        if (policy.adultModeEnabled) throw ContentPolicyException("PHOTO_DISABLED_IN_ADULT_MODE", "Reference-photo generation is disabled in adult mode")
        if (!policy.photoIsSelfOwned || !policy.photoConsentConfirmed) throw ContentPolicyException("PHOTO_CONSENT_REQUIRED", "A reference photo requires self-ownership and consent confirmation")
    }
}
