import Foundation

/// 明文 / 加密帧的统一构造，键名与协议文档保持一致。
public enum MessageFrames {

    public static func helloAck(
        serverId: String,
        serverName: String,
        fingerprint: String,
        known: Bool,
        firstSeen: Date,
        serverNonce: Data?,
        sessionToken: String?
    ) -> [String: Any] {
        var frame: [String: Any] = [
            "type": "hello_ack",
            "v": TB.protocolVersion,
            "cipher": "aes-256-gcm",
            "serverId": serverId,
            "serverName": serverName,
            "serverFingerprint": fingerprint,
            "known": known,
            "firstSeenAt": Int64(firstSeen.timeIntervalSince1970 * 1000)
        ]
        if let serverNonce {
            frame["serverNonce"] = serverNonce.base64EncodedString()
        } else {
            frame["serverNonce"] = NSNull()
        }
        frame["sessionToken"] = sessionToken ?? NSNull()
        return frame
    }

    public static func error(code: Int, message: String) -> [String: Any] {
        ["type": "error", "code": code, "msg": message]
    }

    public static func ack(id: String, ok: Bool) -> [String: Any] {
        ["type": "ack", "id": id, "ok": ok]
    }

    public static func pong() -> [String: Any] {
        ["type": "pong", "ts": Int64(Date().timeIntervalSince1970 * 1000)]
    }

    public static func status(online: Bool, devices: Int) -> [String: Any] {
        ["type": "status", "state": online ? "online" : "offline", "devices": devices]
    }

    public static func actions(version: Int, actions: [ActionItem]) -> [String: Any] {
        let list: [[String: Any]] = actions.map { action in
            [
                "id": action.id,
                "name": action.name,
                "behavior": action.behavior.rawValue,
                // 敏感指令的固定文本永不下发
                "text": action.sensitive ? "" : action.text,
                "order": action.order,
                "sensitive": action.sensitive
            ]
        }
        return ["type": "actions", "version": version, "actions": list]
    }
}
