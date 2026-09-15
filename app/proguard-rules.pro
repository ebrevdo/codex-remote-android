-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
# JCA and BC discover algorithm mappings and implementation classes by name.
# Retain the JCA provider algorithms; unused PKIX/DANE/LDAP desktop clients can shrink.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.MessageProp
-dontwarn org.ietf.jgss.Oid

# SSH key parsing does not use EST/TLS or DANE network clients. A future
# reference must fail release builds instead of retaining trust-all helpers.
-checkdiscard class org.bouncycastle.est.**
-checkdiscard class org.bouncycastle.cert.dane.**
