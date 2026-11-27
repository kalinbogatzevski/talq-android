package talq.core.org

/**
 * The owner's brand signing keys: key id (see [keyIdOf]) -> the raw 32-byte Ed25519 public key in
 * hex. Only files signed by one of these are ever accepted. The private keys never enter a
 * repository; the owner makes them with the issuing tool's `keygen` in a plain terminal and pastes
 * only these two public values here. Empty = every brand file is refused (safe, and how a build
 * without the owner's key behaves).
 *
 * Replacing a key: add the new one, issue with it, and only drop the old one in a later version;
 * dropping a key also drops every stored organisation that only it vouched for (checked at start).
 */
val TRUSTED_BRAND_KEYS: Map<String, String> = mapOf(
    // The owner's first key (made 2026-11-27).
    "f299d387" to "e113e4060092b6a2df3fdd1e641cbfa55b985018cbb3c94105539c62d3ab8f7e",
)
