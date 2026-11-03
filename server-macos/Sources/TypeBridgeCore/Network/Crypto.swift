import CryptoKit
import Foundation

public enum CryptoError: Error {
    case badCiphertext
    case badNonce
}

/// 与 Android 端 §4.2 对齐的加密工具。
///
/// 所有业务帧使用 AES-256-GCM，密文格式为 `iv(12B) || ciphertext || tag(16B)`。
/// 会话密钥用 HKDF-SHA256 从 PSK + 双 nonce 派生。
public enum Crypto {

    /// 会话 ID 由客户端 / 服务端 nonce 确定性派生。
    /// 协议文档 §4.3 只写了“客户端生成 sessionId”，但该值未在 hello/hello_ack 中传递，
    /// 因此双端统一用 nonce 派生，保证 AAD 可复算。
    public static func sessionId(clientNonce: Data, serverNonce: Data) -> String {
        var input = TB.sessionIdInfo
        input.append(clientNonce)
        input.append(serverNonce)
        let digest = SHA256.hash(data: input)
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    public static func deriveSessionKey(psk: Data, clientNonce: Data, serverNonce: Data) -> SymmetricKey {
        let salt = clientNonce + serverNonce
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: psk),
            salt: salt,
            info: TB.sessionInfo,
            outputByteCount: 32
        )
    }

    public static func authTag(psk: Data, deviceId: String, clientNonce: Data) -> Data {
        let key = SymmetricKey(data: psk)
        var data = Data(deviceId.utf8)
        data.append(clientNonce)
        return Data(HMAC<SHA256>.authenticationCode(for: data, using: key))
    }

    /// 返回 `iv(12B) || ciphertext || tag(16B)`。
    public static func encrypt(_ plain: Data, key: SymmetricKey, aad: Data) throws -> Data {
        let box = try AES.GCM.seal(plain, using: key, authenticating: aad)
        var out = Data(box.nonce)
        out.append(box.ciphertext)
        out.append(box.tag)
        return out
    }

    public static func decrypt(key: SymmetricKey, data: Data, aad: Data) throws -> Data {
        let ivLength = 12
        let tagLength = 16
        guard data.count > ivLength + tagLength else { throw CryptoError.badCiphertext }
        let nonceBytes = data.prefix(ivLength)
        let ciphertext = data[ivLength ..< (data.count - tagLength)]
        let tag = data.suffix(tagLength)
        let box = try AES.GCM.SealedBox(
            nonce: try AES.GCM.Nonce(data: nonceBytes),
            ciphertext: ciphertext,
            tag: tag
        )
        return try AES.GCM.open(box, using: key, authenticating: aad)
    }

    /// AAD = utf8(sessionId) || UInt64(counter, big-endian)。
    /// counter 按方向独立计数：发送方加密后 +1，接收方解密成功后 +1。
    public static func aad(sessionId: String, counter: UInt64) -> Data {
        var data = Data(sessionId.utf8)
        var value = counter.bigEndian
        withUnsafeBytes(of: &value) { data.append(contentsOf: $0) }
        return data
    }

    /// sha256(PSK) 前 4 字节，8 位小写 hex。
    public static func fingerprint(_ psk: Data) -> String {
        let digest = SHA256.hash(data: psk)
        return digest.prefix(4).map { String(format: "%02x", $0) }.joined()
    }

    public static func constantTimeEquals(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var diff: UInt8 = 0
        for (a, b) in zip(lhs, rhs) {
            diff |= a ^ b
        }
        return diff == 0
    }

    public static func randomData(_ count: Int) -> Data {
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, count, buffer.baseAddress!)
        }
        if status != errSecSuccess {
            TBLog.server.error("SecRandomCopyBytes failed: \(status)")
            return Data((0 ..< count).map { _ in UInt8.random(in: 0 ... 255) })
        }
        return data
    }
}

extension Data {
    public static func + (lhs: Data, rhs: Data) -> Data {
        var data = lhs
        data.append(rhs)
        return data
    }
}
