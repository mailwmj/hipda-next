package net.jejer.hipda.data.account

import kotlinx.serialization.Serializable

/**
 * Saved forum account credentials persisted in the Tink AEAD-encrypted [CredentialsStore].
 *
 * Only [passwordMd5] (the 32-character lowercase hex MD5 of the escaped password, matching legacy
 * `LoginHelper.java:112,170-181`) is stored instead of the raw plaintext password: Discuz!
 * `logging.php` accepts this MD5 digest directly for both interactive login and silent re-login,
 * and no UI screen ever displays or edits a saved password. Because the digest is still a
 * login-equivalent credential on Discuz!, it is only persisted inside the Tink AEAD-encrypted
 * DataStore and redacted from [toString].
 */
@Serializable
data class SavedAccount(
    val uid: String,
    val username: String,
    val passwordMd5: String,
    val securityQuestionId: Int = NO_SECURITY_QUESTION,
    val securityAnswer: String = "",
    val lastLoginAt: Long,
) {
    override fun toString(): String =
        "SavedAccount(uid=$uid, username=$username, passwordMd5=***, " +
            "securityQuestionId=$securityQuestionId, securityAnswer=***, lastLoginAt=$lastLoginAt)"

    companion object {
        const val NO_SECURITY_QUESTION: Int = 0
    }
}
