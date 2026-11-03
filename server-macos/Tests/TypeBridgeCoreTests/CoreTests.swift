import CryptoKit
import XCTest
@testable import TypeBridgeCore

final class CryptoTests: XCTestCase {

    private let psk = Data(repeating: 0x5A, count: 32)
    private let clientNonce = Data(repeating: 0x11, count: 16)
    private let serverNonce = Data(repeating: 0x22, count: 16)

    func testSessionKeyIsDeterministic() {
        let first = Crypto.deriveSessionKey(psk: psk, clientNonce: clientNonce, serverNonce: serverNonce)
        let second = Crypto.deriveSessionKey(psk: psk, clientNonce: clientNonce, serverNonce: serverNonce)

        let firstBytes = first.withUnsafeBytes { Data($0) }
        let secondBytes = second.withUnsafeBytes { Data($0) }

        XCTAssertEqual(firstBytes, secondBytes)
        XCTAssertEqual(firstBytes.count, 32)
    }

    func testSessionIdIsDeterministicAndDerivedFromNonces() {
        let other = Data(repeating: 0x33, count: 16)
        let id = Crypto.sessionId(clientNonce: clientNonce, serverNonce: serverNonce)

        XCTAssertEqual(id.count, 32)
        XCTAssertEqual(id, Crypto.sessionId(clientNonce: clientNonce, serverNonce: serverNonce))
        XCTAssertNotEqual(id, Crypto.sessionId(clientNonce: clientNonce, serverNonce: other))
    }

    func testAuthTagMatchesBothSides() {
        let tag = Crypto.authTag(psk: psk, deviceId: "device-1", clientNonce: clientNonce)
        let sameTag = Crypto.authTag(psk: psk, deviceId: "device-1", clientNonce: clientNonce)
        let otherDevice = Crypto.authTag(psk: psk, deviceId: "device-2", clientNonce: clientNonce)

        XCTAssertTrue(Crypto.constantTimeEquals(tag, sameTag))
        XCTAssertFalse(Crypto.constantTimeEquals(tag, otherDevice))
        XCTAssertEqual(tag.count, 32)
    }

    func testFingerprintIsEightLowercaseHex() {
        let fingerprint = Crypto.fingerprint(psk)
        XCTAssertEqual(fingerprint.count, 8)
        XCTAssertTrue(fingerprint.allSatisfy { $0.isNumber || ("a" ... "f").contains($0) })
    }

    func testEncryptDecryptRoundTrip() throws {
        let key = Crypto.deriveSessionKey(psk: psk, clientNonce: clientNonce, serverNonce: serverNonce)
        let aad = Crypto.aad(sessionId: "sid", counter: 7)
        let plain = Data("你好 TypeBridge 👋".utf8)

        let ciphertext = try Crypto.encrypt(plain, key: key, aad: aad)

        XCTAssertEqual(ciphertext.count, plain.count + 12 + 16)
        XCTAssertEqual(try Crypto.decrypt(key: key, data: ciphertext, aad: aad), plain)
    }

    func testDecryptRejectsWrongAAD() throws {
        let key = Crypto.deriveSessionKey(psk: psk, clientNonce: clientNonce, serverNonce: serverNonce)
        let ciphertext = try Crypto.encrypt(Data("hello".utf8), key: key, aad: Crypto.aad(sessionId: "a", counter: 0))

        XCTAssertThrowsError(try Crypto.decrypt(key: key, data: ciphertext, aad: Crypto.aad(sessionId: "b", counter: 0)))
    }

    func testSessionCountersRejectReplay() throws {
        let key = Crypto.deriveSessionKey(psk: psk, clientNonce: clientNonce, serverNonce: serverNonce)
        let sessionId = Crypto.sessionId(clientNonce: clientNonce, serverNonce: serverNonce)

        let sender = Session(id: sessionId, key: key, token: "sender")
        let receiver = Session(id: sessionId, key: key, token: "receiver")

        let first = try sender.encrypt(Data("one".utf8))
        let second = try sender.encrypt(Data("two".utf8))

        XCTAssertEqual(try receiver.decrypt(first), Data("one".utf8))
        XCTAssertEqual(try receiver.decrypt(second), Data("two".utf8))

        // 重放第一帧：counter 已推进，AAD 不匹配
        XCTAssertThrowsError(try receiver.decrypt(first))
    }
}

final class QrPayloadTests: XCTestCase {

    func testUriContainsAllRequiredFields() {
        let payload = QrPayload(
            ip: "192.168.1.10",
            mdns: "typebridge-abcd.local",
            port: 8765,
            psk: Data(repeating: 0x01, count: 32),
            fingerprint: "a1b2c3d4",
            expiresAt: Date(timeIntervalSince1970: 1_700_000_000)
        )

        let uri = payload.uriString
        XCTAssertTrue(uri.hasPrefix("typebridge://connect?"))

        let items = URLComponents(string: uri)?.queryItems ?? []
        XCTAssertEqual(items.first { $0.name == "v" }?.value, "2")
        XCTAssertEqual(items.first { $0.name == "ip" }?.value, "192.168.1.10")
        XCTAssertEqual(items.first { $0.name == "mdns" }?.value, "typebridge-abcd.local")
        XCTAssertEqual(items.first { $0.name == "port" }?.value, "8765")
        XCTAssertEqual(items.first { $0.name == "fp" }?.value, "a1b2c3d4")
        XCTAssertEqual(items.first { $0.name == "psk" }?.value, payload.psk.base64EncodedString())
        XCTAssertEqual(items.first { $0.name == "expires" }?.value, "1700000000000")
    }

    func testUriPercentEncodesBase64Characters() {
        // 0xFB 的 base64 一定包含 '+' 和 '/'，用于验证 query 转义
        let psk = Data(repeating: 0xFB, count: 32)
        let payload = QrPayload(
            ip: "192.168.1.10",
            mdns: nil,
            port: 8765,
            psk: psk,
            fingerprint: "a1b2c3d4",
            expiresAt: Date(timeIntervalSince1970: 1_700_000_000)
        )

        let uri = payload.uriString
        XCTAssertTrue(uri.contains("psk=%2B"), "base64 的 '+' 必须被转义，否则 Android 会解码成空格")
        XCTAssertFalse(uri.contains("psk=+"))

        let decoded = URLComponents(string: uri)?
            .queryItems?
            .first { $0.name == "psk" }?
            .value
        XCTAssertEqual(decoded, psk.base64EncodedString())
    }
}

final class SessionStoreTests: XCTestCase {

    func testIPv4MappedAddressIsNormalized() {
        XCTAssertEqual("::ffff:192.168.1.10".ipv4Normalized, "192.168.1.10")
        XCTAssertEqual("192.168.1.10".ipv4Normalized, "192.168.1.10")
        XCTAssertEqual("fe80::1".ipv4Normalized, "fe80::1")
    }

    func testLookupReturnsStoredRecord() {
        let store = SessionStore.shared
        store.removeAll()

        let key = SymmetricKey(size: .bits256)
        let record = SessionRecord(
            token: "token-1",
            sessionId: "sid-1",
            key: key,
            deviceId: "device-1",
            fingerprint: "deadbeef",
            createdAt: Date()
        )
        store.put(record)

        XCTAssertEqual(store.lookup(token: "token-1")?.deviceId, "device-1")
        XCTAssertNil(store.lookup(token: "token-2"))
        store.removeAll()
    }
}
