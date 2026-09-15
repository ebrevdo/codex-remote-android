package com.codex.remote.data.ssh

import net.schmizz.sshj.DefaultSecurityProviderConfig
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security

/**
 * SSHJ takes a provider name, not a Provider instance. Give the bundled BC
 * implementation its own name instead of replacing Android's built-in BC.
 * This supplies Ed25519 on Android versions whose platform JCA lacks it.
 */
private class SshCryptoProvider : Provider(NAME, 1.0, "Codex Remote SSH cryptography") {
    init {
        putAll(BouncyCastleProvider())
    }

    companion object {
        const val NAME = "CodexRemoteSSH"
    }
}

@Synchronized
internal fun androidCompatibleSshConfig(): DefaultSecurityProviderConfig {
    if (Security.getProvider(SshCryptoProvider.NAME) == null) {
        check(Security.addProvider(SshCryptoProvider()) != -1) { "Unable to initialize SSH cryptography" }
    }
    SecurityUtils.setRegisterBouncyCastle(false)
    SecurityUtils.setSecurityProvider(SshCryptoProvider.NAME)
    return DefaultSecurityProviderConfig().apply {
        // Explicit policy: library updates cannot silently enable weak fallbacks.
        keyExchangeFactories = keyExchangeFactories.filter { it.name in allowedKeyExchanges }
        cipherFactories = cipherFactories.filter { it.name in allowedCiphers }
        macFactories = macFactories.filter { it.name in allowedMacs }
        keyAlgorithms = keyAlgorithms.filter { it.name in allowedHostKeyAlgorithms }
        check(keyExchangeFactories.isNotEmpty() && cipherFactories.isNotEmpty() &&
            macFactories.isNotEmpty() && keyAlgorithms.isNotEmpty()) { "No supported secure SSH algorithms" }
    }
}

private val allowedKeyExchanges = setOf(
    "curve25519-sha256", "curve25519-sha256@libssh.org",
    "ecdh-sha2-nistp256", "ecdh-sha2-nistp384", "ecdh-sha2-nistp521",
    "diffie-hellman-group14-sha256", "diffie-hellman-group16-sha512", "diffie-hellman-group18-sha512",
    "ext-info-c", // Protocol extension marker, not a key exchange algorithm.
)
private val allowedCiphers = setOf(
    "chacha20-poly1305@openssh.com", "aes128-gcm@openssh.com", "aes256-gcm@openssh.com",
    "aes128-ctr", "aes192-ctr", "aes256-ctr",
)
private val allowedMacs = setOf("hmac-sha2-256-etm@openssh.com", "hmac-sha2-512-etm@openssh.com")
private val allowedHostKeyAlgorithms = setOf(
    "ssh-ed25519", "ssh-ed25519-cert-v01@openssh.com",
    "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
    "ecdsa-sha2-nistp256-cert-v01@openssh.com", "ecdsa-sha2-nistp384-cert-v01@openssh.com",
    "ecdsa-sha2-nistp521-cert-v01@openssh.com", "rsa-sha2-256", "rsa-sha2-512",
)
