package com.codex.remote.data.ssh

import com.codex.remote.domain.RemotePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import net.schmizz.sshj.common.SecurityUtils
import java.security.Security

class SshConfigTest {
    @Test
    fun excludesLegacyAlgorithmsAndInstantiatesModernFactories() {
        val config = androidCompatibleSshConfig()
        val names = config.keyExchangeFactories.map { it.name }
        assertTrue(names.any { it.contains("curve25519") })
        assertFalse(names.any { it.contains("sha1") || it.contains("group1-") })
        assertFalse(config.cipherFactories.any { it.name.contains("cbc") || it.name.contains("arcfour") || it.name == "none" })
        assertTrue(config.macFactories.all { it.name.startsWith("hmac-sha2-") && it.name.endsWith("-etm@openssh.com") })
        assertFalse(config.keyAlgorithms.any { it.name == "ssh-rsa" || it.name.contains("dss") })
        config.keyExchangeFactories.forEach { it.create() }
        config.cipherFactories.forEach { it.create() }
        config.macFactories.forEach { it.create() }
    }

    @Test
    fun bundledProviderSignsEd25519WithoutReplacingPlatformProvider() {
        val originalBc = Security.getProvider("BC")
        androidCompatibleSshConfig()
        assertEquals(originalBc, Security.getProvider("BC"))
        val keys = SecurityUtils.getKeyPairGenerator("Ed25519").generateKeyPair()
        val message = "SSH signature regression".toByteArray()
        val signer = SecurityUtils.getSignature("Ed25519")
        signer.initSign(keys.private)
        signer.update(message)
        val signature = signer.sign()
        val verifier = SecurityUtils.getSignature("Ed25519")
        verifier.initVerify(keys.public)
        verifier.update(message)
        assertTrue(verifier.verify(signature))
        verifier.initVerify(keys.public)
        verifier.update("changed message".toByteArray())
        assertFalse(verifier.verify(signature))
        SecurityUtils.getKeyPairGenerator("X25519").generateKeyPair()
    }

    @Test
    fun posixCommandsUseTheRemoteLoginShell() {
        assertEquals(
            "exec \"\${SHELL:-/bin/sh}\" -lc 'codex --version'",
            codexVersionCommand(RemotePlatform.POSIX),
        )
        assertTrue(appServerCommand(RemotePlatform.POSIX).contains("-lc 'exec codex app-server"))
    }

    @Test
    fun windowsCommandAllowsTheRemotePowerShellProfile() {
        val command = appServerCommand(RemotePlatform.WINDOWS)

        assertTrue(command.contains("powershell.exe"))
        assertFalse(command.contains("-NoProfile"))
        assertTrue(command.contains("codex app-server --listen stdio://"))
    }
}
