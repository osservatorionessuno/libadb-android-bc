# LibADB Android

ADB library for Android. It enables an app to connect to the ADB daemon (`adbd` process) belonging to the same or a
different device and execute arbitrary services or commands (via `shell:` service).

> **Notice:** This is a fork of the original
> [MuntashirAkon/libadb-android](https://github.com/MuntashirAkon/libadb-android). It is backed entirely by
> [BouncyCastle](https://www.bouncycastle.org/) with some wrapper to implement both the custom TLS exporter and the custom BoringSSL spake2 implementation.

**Disclaimer:** This library has never gone through a security audit. Please, proceed with caution if security is
crucial for your app. Avoid using the APIs for reasons other than connecting or using ADB. For the safety of your app
and its users, open a remote service instead of using ADB and ask the user to disconnect Wireless debugging.

## Getting Started
### Adding Dependencies
LibADB Android is available via JitPack.

```groovy
// Top level build file
repositories {
    mavenCentral()
    maven { url "https://jitpack.io" }
}

// Add to dependencies section
dependencies {
    // Add this library (this fork). The version is a JitPack tag/commit — create a
    // matching git tag (e.g. 3.2.0) on the fork, or use a commit hash.
    implementation 'com.github.osservatorionessuno:libadb-android-bc:3.2.0'
    
    // BouncyCastle, used in the example below to generate the X509Certificate
    // and write it as PEM. See example for use-case.
    implementation 'org.bouncycastle:bcprov-jdk15to18:1.84'
    implementation 'org.bouncycastle:bcpkix-jdk15to18:1.84'
}
```

TLS (including the TLSv1.3 handshake used by ADB pairing) is provided internally by BouncyCastle's JSSE provider, which
ships transitively with the library — you don't need Conscrypt or any other TLS provider.

If your app targets Android versions below 4.4 (API 19), extend `android.app.Application` to apply the `SecureRandom`
fixes before any key generation:
```java
public class MyAwesomeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Fix random number generation in Android versions below 4.4.
        PRNGFixes.apply();
    }
}
```

**Notice:** ADB pairing and the TLSv1.3 handshake require API 9 (Gingerbread) or later. The corresponding methods are
annotated with `@RequiresApi`, so you don't have to worry about compatibility issues that may arise when your app's
minimum SDK is set to one of the unsupported versions.

### Configuring ADB
Instead of doing everything manually, you can create a concrete implementation of the `AbsAdbConnectionManager` class. 
Example:

```java
public class AdbConnectionManager extends AbsAdbConnectionManager {
    private static AbsAdbConnectionManager INSTANCE;

    public static AbsAdbConnectionManager getInstance() throws Exception {
        if (INSTANCE == null) {
            INSTANCE = new AdbConnectionManager();
        }
        return INSTANCE;
    }

    private PrivateKey mPrivateKey;
    private Certificate mCertificate;

    private AdbConnectionManager() throws Exception {
        // Set the API version whose `adbd` is running
        setApi(Build.VERSION.SDK_INT);
        // TODO: Load private key and certificate (along with public key) from
        //  some place such as KeyStore or file system.
        mPrivateKey = ...;
        mCertificate = ...;
        if (mPrivateKey == null) {
            // Generate a new key pair
            int keySize = 2048;
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(keySize, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair generateKeyPair = keyPairGenerator.generateKeyPair();
            PublicKey publicKey = generateKeyPair.getPublic();
            mPrivateKey = generateKeyPair.getPrivate();
            // Generate a self-signed certificate with BouncyCastle.
            String subject = "CN=My Awesome App";
            String algorithmName = "SHA512withRSA";
            Date notBefore = new Date();
            Date notAfter = new Date(System.currentTimeMillis() + 86400000);
            BigInteger serialNumber = BigInteger.valueOf(new Random().nextInt() & Integer.MAX_VALUE);
            X500Name x500Name = new X500Name(subject);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    x500Name, serialNumber, notBefore, notAfter, x500Name, publicKey);
            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    extUtils.createSubjectKeyIdentifier(publicKey));
            // PrivateKeyUsagePeriod ::= SEQUENCE { notBefore [0] GeneralizedTime, notAfter [1] GeneralizedTime }
            ASN1EncodableVector period = new ASN1EncodableVector();
            period.add(new DERTaggedObject(false, 0, new ASN1GeneralizedTime(notBefore)));
            period.add(new DERTaggedObject(false, 1, new ASN1GeneralizedTime(notAfter)));
            builder.addExtension(Extension.privateKeyUsagePeriod, false,
                    PrivateKeyUsagePeriod.getInstance(new DERSequence(period)));
            ContentSigner signer = new JcaContentSignerBuilder(algorithmName).build(mPrivateKey);
            mCertificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
            // TODO: Store the key pair to some place else.
        }
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "MyAwesomeApp";
    }
}
```

### Connecting to ADB

You can connect to ADB in several ways from the `AbsAdbConnectionManager`:

| Method                          | Description                                                                                                   |
|---------------------------------|---------------------------------------------------------------------------------------------------------------|
| `connect(host, port)`           | Connect using a host address and a port number                                                                |
| `connect(port)`                 | Connect using a host address set by `setHostAddress()` and a port number                                      |
| `connectTcp(Context, timeout)`  | (SDK 16+) Discover host address and port number automatically for ADB over TCP and connect to it              | 
| `connectTls(Context, timeout)`  | (SDK 16+) Discover host address and port number automatically for TLS (from Android 9) and connect to it      |
| `autoConnect(Context, timeout)` | (SDK 16+) Discover host address and port number automatically for both ADB over TCP and TLS and connect to it |

### Wireless Debugging
Internally, ADB over TCP and Wireless Debugging are very similar except Wireless Debugging requires an extra step of
_pairing_ the device. In order to pair a new device, you can simply invoke `AdbConnectionManager.getInstance().pair(host, port, pairingCode)`.
After the pairing, you can connect to ADB via the usual `connect()` methods without any additional steps.

### Opening ADB Shell for Executing Arbitrary Commands
Simply use `AdbConnectionManager.getInstance().openStream("shell:")`. This will return an `AdbStream` which can be used
to read/write to the ADB shell via `AdbStream#openInputStream()` and `AdbStream#openOutputStream()` methods
respectively like a normal Java `Process`. While it is possible to read/write in the same thread (first write and then
read), this is not recommended because the shell might be stuck indefinitely for commands such as `top`.

**NOTE:** If you want to create a full-featured terminal emulator, this approach isn't recommended. Instead, you should
create a remote service via `app_process` or start an SSH server and connect to it.

### Other services
You can also use other services via the `AdbConnectionManager#openStream()` methods. See [SERVICES.md](./SERVICES.md)
for more information.

## For Java (non-Android) Projects
It is possible to modify this library to work on non-Android project. But it isn't supported because Spake2-Java only
provides stable releases for Android. However, you can incorporate this library in your project by manually compiling
Spake2 library for your platforms.

## Contributing
By contributing to this project, you permit your work to be released under the terms of GNU General Public License, 
Version 3 or later **or** Apache License, Version 2.0.

## License
Copyright 2021 &copy; Muntashir Al-Islam

Dual licensed under the terms of [GPL-3.0-or-later](https://www.gnu.org/licenses/gpl-3.0.html) or
[Apache-2.0 license](https://www.apache.org/licenses/LICENSE-2.0.html). Use whatever license you need for your project.

_Note regarding the Apache-2.0 license, this library has an LGPL dependency which may go against the policy of some
organizations such as ASF._
