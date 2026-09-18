# JLaTeXMath registers built-in macros (including \operatorname) by class and
# method name through MacroInfo. Keep only these reflective entry points.
-keep class org.scilab.forge.jlatexmath.NewCommandMacro {
    public <init>();
    public java.lang.String executeMacro(org.scilab.forge.jlatexmath.TeXParser, java.lang.String[]);
}

# TeXFormula also discovers the bundled alphabet registrations by class name.
-keep class org.scilab.forge.jlatexmath.cyrillic.CyrillicRegistration {
    public <init>();
}
-keep class org.scilab.forge.jlatexmath.greek.GreekRegistration {
    public <init>();
}

# The app renders bundled formulas only; it never loads external alphabet JARs
# or XML definitions that invoke arbitrary Java methods.
-checkdiscard class org.scilab.forge.jlatexmath.URLAlphabetRegistration
-checkdiscard class org.scilab.forge.jlatexmath.WebStartAlphabetRegistration
-checkdiscard class org.scilab.forge.jlatexmath.PredefinedTeXFormulaParser
-checkdiscard class org.scilab.forge.jlatexmath.TeXFormulaParser

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
