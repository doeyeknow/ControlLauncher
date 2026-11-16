package app.control.launcher;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateEntry;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientContext;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyPair;
import java.security.SecureRandom;

/**
 * The one Bouncy Castle TLS 1.3 client stack both ADB paths use
 * (v1.4). Pairing proved this stack against a real adbd in v1.2,
 * while the bridge-start connect path still used the framework
 * SSLContext (Conscrypt) — and on Adam's phone that second stack's
 * handshake died before the ADB CNXN exchange even though the same
 * certificate bytes, presented through this stack, paired fine.
 * One stack, one behavior: this class is the scaffolding, shared
 * verbatim by {@link AdbPairing} and {@link AdbClient}.
 *
 * The shape mirrors AOSP's tls_connection: TLS 1.3 only, mutual
 * certificates, the peer certificate accepted as-is (ADB security
 * is that the phone only authorizes paired keys, and only offers
 * these services while wireless debugging is on). Control presents
 * the stored {@link AdbKeyStore} identity — the exact {@code certDer}
 * bytes the caller passes in.
 */
final class AdbTls {

    private AdbTls() {}

    /** An established TLS session over a plain socket. */
    static final class Connection {
        private final Socket socket;
        private final TlsClientProtocol protocol;
        private final Client client;

        private Connection(Socket socket, TlsClientProtocol protocol, Client client) {
            this.socket = socket;
            this.protocol = protocol;
            this.client = client;
        }

        InputStream inputStream() {
            return protocol.getInputStream();
        }

        OutputStream outputStream() {
            return protocol.getOutputStream();
        }

        /** The 64-byte "adb-label" exporter secret, captured inside
         *  notifyHandshakeComplete (BC nulls it after connect()
         *  returns). Pairing keys its SPAKE2 password on this; the
         *  connect path derives it too and simply doesn't use it. */
        byte[] exportedKeyMaterial() {
            return client.exportedKeyMaterial();
        }

        void close() {
            try {
                protocol.close();
            } catch (Exception ignored) {
            }
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Connect and complete the TLS 1.3 handshake. Any failure —
     * refused, timeout, a fatal alert from the phone — propagates
     * as the original exception, so callers can report its class
     * and message verbatim.
     */
    static Connection connect(KeyPair keyPair, byte[] certDer, InetAddress host,
                              int port, int connectTimeoutMs, int soTimeoutMs)
            throws IOException {
        Socket socket = new Socket();
        boolean ok = false;
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(soTimeoutMs);
            BcTlsCrypto crypto = new BcTlsCrypto(new SecureRandom());
            Client client = new Client(crypto, keyPair, certDer);
            TlsClientProtocol protocol = new TlsClientProtocol(
                    socket.getInputStream(), socket.getOutputStream());
            protocol.connect(client);
            ok = true;
            return new Connection(socket, protocol, client);
        } finally {
            if (!ok) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** The TLS client itself: TLS 1.3 only, accept-any peer cert,
     *  Control's stored identity as the client credential. This is
     *  byte-for-byte the client pairing shipped in v1.2 (it lived
     *  inside AdbPairing until v1.4 factored it out). */
    static final class Client extends DefaultTlsClient {
        private final KeyPair keyPair;
        private final byte[] certDer;
        private volatile TlsClientContext context;
        private volatile byte[] exportedKeyMaterial;

        Client(BcTlsCrypto crypto, KeyPair keyPair, byte[] certDer) {
            super(crypto);
            this.keyPair = keyPair;
            this.certDer = certDer;
        }

        @Override
        public void init(TlsClientContext context) {
            super.init(context);
            this.context = context;
        }

        byte[] exportedKeyMaterial() {
            return exportedKeyMaterial;
        }

        /** The one moment BC hands out the exporter secret. */
        @Override
        public void notifyHandshakeComplete() throws IOException {
            super.notifyHandshakeComplete();
            exportedKeyMaterial = context.exportKeyingMaterial(
                    "adb-label\0", null, 64);
        }

        /** TLS 1.3 only, like AOSP's tls_connection. */
        @Override
        protected ProtocolVersion[] getSupportedVersions() {
            return new ProtocolVersion[]{ProtocolVersion.TLSv13};
        }

        @Override
        public TlsAuthentication getAuthentication() {
            return new TlsAuthentication() {
                /** AOSP accepts any peer certificate here (pairing
                 *  installs a verify callback returning 1; the
                 *  connect service likewise authenticates by the
                 *  client key it has authorized, not by chains). */
                @Override
                public void notifyServerCertificate(TlsServerCertificate serverCertificate) {
                    try {
                        if (serverCertificate != null
                                && serverCertificate.getCertificate() != null) {
                            TlsCertificate[] chain = serverCertificate
                                    .getCertificate().getCertificateList();
                            if (chain != null && chain.length > 0) {
                                AdbKeyStore.lastPeerFingerprint =
                                        AdbKeyStore.fingerprint(chain[0].getEncoded());
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public TlsCredentials getClientCredentials(CertificateRequest request)
                        throws IOException {
                    BcTlsCrypto bc = (BcTlsCrypto) getCrypto();
                    AsymmetricKeyParameter priv = PrivateKeyFactory.createKey(
                            keyPair.getPrivate().getEncoded());
                    TlsCertificate cert = new BcTlsCertificate(bc, certDer);
                    // TLS 1.3 Certificate shape: BC refuses to encode
                    // the legacy Certificate(TlsCertificate[]) under
                    // TLS 1.3 (its encode requires a non-null request
                    // context). Echo the context from the phone's
                    // CertificateRequest (empty in these handshakes),
                    // per RFC 8446.
                    byte[] reqCtx = request == null
                            ? null : request.getCertificateRequestContext();
                    Certificate certMsg = new Certificate(
                            reqCtx == null ? new byte[0] : reqCtx,
                            new CertificateEntry[]{new CertificateEntry(cert, null)});
                    return new BcDefaultTlsCredentialedSigner(
                            new TlsCryptoParameters(context), bc, priv,
                            certMsg,
                            SignatureAndHashAlgorithm.rsa_pss_rsae_sha256);
                }
            };
        }
    }
}
