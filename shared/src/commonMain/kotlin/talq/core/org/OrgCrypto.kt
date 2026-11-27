package talq.core.org

// The cryptography organisation mode needs, from each platform's own implementation (androidMain:
// the system's javax.crypto and Tink; jvmMain: the JDK and Tink), so the phone and the offline JVM
// checks and the owner's issuing tool run the same common code around them. Every function here
// answers "no" (null or false) instead of throwing for bad input: a brand file is untrusted bytes.

/** SHA-256 of [data] (32 bytes). */
internal expect fun sha256(data: ByteArray): ByteArray

/** HMAC-SHA256 of [data] under [key] (32 bytes). */
internal expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** PBKDF2-HMAC-SHA256 of an ASCII [password]: [length] bytes. */
internal expect fun pbkdf2Sha256(password: String, salt: ByteArray, iterations: Int, length: Int): ByteArray

/**
 * AES-256-GCM (128-bit tag) opening of [sealed] (ciphertext then tag) with [nonce] (12 bytes) and the
 * additional data [aad]. Null when it does not authenticate, or for any other failure.
 */
internal expect fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?

/**
 * Whether [signature] (64 bytes) is a valid Ed25519 signature of [message] under the raw 32-byte
 * [publicKey]. False for any failure, a wrong key or signature length included.
 */
internal expect fun ed25519Verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean
