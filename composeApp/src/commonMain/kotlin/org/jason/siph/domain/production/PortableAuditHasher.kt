package org.jason.siph.domain.production

/**
 * Cross-platform deterministic fallback for previews and non-JVM targets.
 * JVM production uses JvmSha256AuditHasher instead.
 */
class PortableAuditHasher : AuditHasher {
    override fun hash(canonicalValue: String): String {
        var first = 0xcbf29ce484222325UL
        var second = 0x9e3779b97f4a7c15UL
        canonicalValue.encodeToByteArray().forEachIndexed { index, byte ->
            val value = byte.toUByte().toULong()
            first = (first xor value) * 0x100000001b3UL
            second = (second xor (value + index.toULong())) * 0x9e3779b185ebca87UL
        }
        return first.toString(16).padStart(16, '0') + second.toString(16).padStart(16, '0')
    }
}
